package dev.jeschke.statify;

import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.SpotifyHttpManager;
import com.wrapper.spotify.enums.ModelObjectType;
import com.wrapper.spotify.exceptions.SpotifyWebApiException;
import com.wrapper.spotify.model_objects.IPlaylistItem;
import com.wrapper.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import com.wrapper.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import com.wrapper.spotify.model_objects.specification.Context;
import com.wrapper.spotify.model_objects.specification.PlayHistory;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeRequest;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;
import org.apache.hc.core5.http.ParseException;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {

    private final static String ACCESS_TOKEN = System.getenv("SPOTIFY_ACCESS_TOKEN");
    private final static String REFRESH_TOKEN = System.getenv("SPOTIFY_REFRESH_TOKEN");
    private final static String CLIENT_ID = System.getenv("SPOTIFY_CLIENT_ID");
    private final static String CLIENT_SECRET = System.getenv("SPOTIFY_CLIENT_SECRET");
    private final static URI REDIRECT_URI = SpotifyHttpManager.makeUri(System.getenv("SPOTIFY_REDIRECT_URI"));
    private final static String ACCESS_CODE = System.getenv("SPOTIFY_ACCESS_CODE");

    private final static ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) {
        final SpotifyApi spotifyApi = new SpotifyApi.Builder()
                .setClientId(CLIENT_ID)
                .setClientSecret(CLIENT_SECRET)
                .setRedirectUri(REDIRECT_URI)
                .setAccessToken(ACCESS_TOKEN)
                .setRefreshToken(REFRESH_TOKEN)
                .build();
        if (ACCESS_CODE == null) {
            startOAuth(spotifyApi);
            return;
        }
        int refreshTime = 0;
        if (ACCESS_TOKEN == null) {
            refreshTime = requestAccessToken(spotifyApi);
        }
        if (refreshTime < 0) {
            return;
        }
        executorService.schedule(() -> refreshTokens(spotifyApi), refreshTime, TimeUnit.SECONDS);

        try {
            System.out.println("preparing connection");
            final Connection connection = DriverManager.getConnection("jdbc:sqlite:statify2.db");
            System.out.println("connection established");
            final Statement statement = connection.createStatement();
            statement.executeUpdate("create table if not exists tracks (songUri text, playTime integer, progress integer, device text, isShuffling boolean, contextUri text)");
            System.out.println("created table");

            executorService.scheduleAtFixedRate(() -> queryCurrentState(spotifyApi, statement), 0, 1, TimeUnit.MINUTES);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    private static void queryCurrentState(SpotifyApi spotifyApi, Statement statement) {
        try {
            final CurrentlyPlayingContext context = spotifyApi.getInformationAboutUsersCurrentPlayback().build().execute();
            if (context == null) {
                System.out.println("Context was null, result probably didn't change");
                return;
            }
            final IPlaylistItem item = context.getItem();
            if (item == null || !context.getIs_playing()) {
                System.out.println("not playing");
                return;
            }
            System.out.println("playing");
            final String name = item.getName();
            final Context context1 = context.getContext();
            final String contextUri;
            if (context1 == null) {
                contextUri = "";
            } else {
                contextUri = context1.getUri();
            }
            System.out.println("Name: " + name + ", Context: " + contextUri);
            statement.executeUpdate("insert into tracks VALUES ('" + item.getUri() + "', " + context.getTimestamp() + ", " + context.getProgress_ms() + ", '" + context.getDevice().getId() + "', " + context.getShuffle_state() + ", '" + contextUri + "')");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void refreshTokens(SpotifyApi spotifyApi) {
        final AuthorizationCodeRefreshRequest authorizationCodeRefreshRequest = spotifyApi.authorizationCodeRefresh().build();
        Integer refreshTime = 10;
        try {
            final AuthorizationCodeCredentials authorizationCodeCredentials = authorizationCodeRefreshRequest.execute();
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            refreshTime = authorizationCodeCredentials.getExpiresIn();
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Couldn't refresh access token. Trying again in 10s.");
            e.printStackTrace();
        }
        executorService.schedule(() -> refreshTokens(spotifyApi), refreshTime, TimeUnit.SECONDS);
    }

    private static int requestAccessToken(SpotifyApi spotifyApi) {
        final AuthorizationCodeRequest authorizationCodeRequest = spotifyApi.authorizationCode(ACCESS_CODE)
                .build();
        try {
            final AuthorizationCodeCredentials authorizationCodeCredentials = authorizationCodeRequest.execute();
            spotifyApi.setAccessToken(authorizationCodeCredentials.getAccessToken());
            spotifyApi.setRefreshToken(authorizationCodeCredentials.getRefreshToken());
            System.out.println("Received access tokens and refresh tokens. Will expire in: " + authorizationCodeCredentials.getExpiresIn() + " seconds");
            System.out.println("Access Token: " + authorizationCodeCredentials.getAccessToken());
            System.out.println("Refresh Token: " + authorizationCodeCredentials.getRefreshToken());
            return authorizationCodeCredentials.getExpiresIn();
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            System.err.println("Couldn't request Tokens.");
            e.printStackTrace();
            return -1;
        }
    }

    private static void startOAuth(SpotifyApi spotifyApi) {
        final AuthorizationCodeUriRequest authorizationCodeUriRequest = spotifyApi.authorizationCodeUri()
                .scope("user-read-recently-played,user-read-playback-state,user-read-currently-playing,playlist-read-private,playlist-read-collaborative,user-library-read")
                .build();
        final URI authCodeUri = authorizationCodeUriRequest.execute();
        System.out.println("You need to get an access code. Open the following webpage: " + authCodeUri);
    }
}
