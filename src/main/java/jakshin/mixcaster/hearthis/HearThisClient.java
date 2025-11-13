/*
 * Copyright (c) 2021 Jason Jackson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package jakshin.mixcaster.hearthis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakshin.mixcaster.http.ServableFile;
import jakshin.mixcaster.mixcloud.MusicSet;
import jakshin.mixcaster.podcast.Podcast;
import jakshin.mixcaster.podcast.PodcastEpisode;
import jakshin.mixcaster.utils.MimeHelper;
import jakshin.mixcaster.utils.TimeSpanFormatter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;

import static jakshin.mixcaster.logging.Logging.*;

/**
 * Queries HearThis.at's REST API.
 */
public class HearThisClient {
    /**
     * Creates an instance, using the given host and port when creating URLs to music files.
     * @param localHostAndPort The local host and port from which music files will be served.
     */
    public HearThisClient(@NotNull String localHostAndPort) {
        this.localHostAndPort = localHostAndPort;
    }

    /**
     * Queries the given user's tracks, likes, or playlists.
     *
     * @param musicSet The set of music desired (HearThis user, music type, playlist slug if applicable).
     * @return A Podcast object containing info about the user and their music.
     */
    @NotNull
    public Podcast query(@NotNull MusicSet musicSet)
            throws InterruptedException, IOException, HearThisException, TimeoutException, URISyntaxException {

        long started = System.nanoTime();

        String musicType = musicSet.musicType();
        if (musicType == null) musicType = "tracks";  // default to tracks

        Podcast podcast = switch (musicType) {
            case "tracks", "shows" -> this.queryTracks(musicSet.username());
            case "favorites", "likes" -> this.queryLikes(musicSet.username());
            case "playlists" -> this.queryPlaylists(musicSet.username());
            default -> throw new IllegalArgumentException("Unexpected music type: " + musicSet.musicType());
        };

        long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000;
        String timeSpan = TimeSpanFormatter.formatTimeSpan((int) elapsedSeconds);
        logger.log(INFO, "Finished querying {0}''s {1} in {2}",
                new String[] { musicSet.username(), musicSet.musicType(), timeSpan });

        return podcast;
    }

    /**
     * Queries the given HearThis user's tracks.
     *
     * @param username The HearThis user whose tracks are desired.
     * @return A Podcast object containing info about the user and their tracks.
     */
    @NotNull
    private Podcast queryTracks(@NotNull String username)
            throws InterruptedException, IOException, HearThisException, TimeoutException, URISyntaxException {

        logger.log(INFO, "Querying {0}''s tracks on HearThis", username);

        Podcast podcast = getPodcastForUser(username);
        podcast.title = String.format("%s's tracks", podcast.iTunesAuthorAndOwnerName);
        podcast.link = new URI(HEARTHIS_WEB + username + "/");

        int episodeCount = 0, maxEpisodeCount = getEpisodeMaxCountSetting();
        int page = 1;
        var context = new QueryContext();

        while (true) {
            String url = String.format("%s%s/?type=tracks&page=%d&count=20", API_BASE_URL, username, page);
            context.description = String.format("HearThis tracks query for %s (page: %d)", username, page);

            JsonArray tracks = fetchJsonArray(url, context.description);
            if (tracks == null || tracks.isEmpty()) {
                logger.log(DEBUG, "{0} has no more tracks", context.description);
                break;
            }

            for (JsonElement element : tracks) {
                if (addEpisodeToPodcast(element.getAsJsonObject(), podcast, context)) {
                    episodeCount++;

                    if (episodeCount >= maxEpisodeCount) {
                        logger.log(DEBUG, "{0} reached max episode count: {1}",
                                new String[]{ context.description, String.valueOf(maxEpisodeCount) });
                        break;
                    }
                }
            }

            if (episodeCount >= maxEpisodeCount) break;
            if (tracks.size() < 20) {
                logger.log(DEBUG, "{0} has no more pages", context.description);
                break;
            }

            page++;
        }

        context.shutdown();
        return removeInvalidEpisodes(podcast);
    }

    /**
     * Queries the given HearThis user's likes/favorites.
     *
     * @param username The HearThis user whose likes are desired.
     * @return A Podcast object containing info about the user and their likes.
     */
    @NotNull
    private Podcast queryLikes(@NotNull String username)
            throws InterruptedException, IOException, HearThisException, TimeoutException, URISyntaxException {

        logger.log(INFO, "Querying {0}''s likes on HearThis", username);

        Podcast podcast = getPodcastForUser(username);
        podcast.title = String.format("%s's favorites", podcast.iTunesAuthorAndOwnerName);
        podcast.link = new URI(HEARTHIS_WEB + username + "/");

        int episodeCount = 0, maxEpisodeCount = getEpisodeMaxCountSetting();
        int page = 1;
        var context = new QueryContext();

        while (true) {
            String url = String.format("%s%s/?type=likes&page=%d&count=20", API_BASE_URL, username, page);
            context.description = String.format("HearThis likes query for %s (page: %d)", username, page);

            JsonArray tracks = fetchJsonArray(url, context.description);
            if (tracks == null || tracks.isEmpty()) {
                logger.log(DEBUG, "{0} has no more likes", context.description);
                break;
            }

            for (JsonElement element : tracks) {
                if (addEpisodeToPodcast(element.getAsJsonObject(), podcast, context)) {
                    episodeCount++;

                    if (episodeCount >= maxEpisodeCount) {
                        logger.log(DEBUG, "{0} reached max episode count: {1}",
                                new String[]{ context.description, String.valueOf(maxEpisodeCount) });
                        break;
                    }
                }
            }

            if (episodeCount >= maxEpisodeCount) break;
            if (tracks.size() < 20) {
                logger.log(DEBUG, "{0} has no more pages", context.description);
                break;
            }

            page++;
        }

        context.shutdown();
        return removeInvalidEpisodes(podcast);
    }

    /**
     * Queries the given HearThis user's playlists.
     *
     * @param username The HearThis user whose playlists are desired.
     * @return A Podcast object containing info about the user and their playlists.
     */
    @NotNull
    private Podcast queryPlaylists(@NotNull String username)
            throws InterruptedException, IOException, HearThisException, TimeoutException, URISyntaxException {

        logger.log(INFO, "Querying {0}''s playlists on HearThis", username);

        Podcast podcast = getPodcastForUser(username);
        podcast.title = String.format("%s's playlists", podcast.iTunesAuthorAndOwnerName);
        podcast.link = new URI(HEARTHIS_WEB + username + "/");

        int episodeCount = 0, maxEpisodeCount = getEpisodeMaxCountSetting();
        int page = 1;
        var context = new QueryContext();

        while (true) {
            String url = String.format("%s%s/?type=playlists&page=%d&count=20", API_BASE_URL, username, page);
            context.description = String.format("HearThis playlists query for %s (page: %d)", username, page);

            JsonArray tracks = fetchJsonArray(url, context.description);
            if (tracks == null || tracks.isEmpty()) {
                logger.log(DEBUG, "{0} has no more playlists", context.description);
                break;
            }

            for (JsonElement element : tracks) {
                if (addEpisodeToPodcast(element.getAsJsonObject(), podcast, context)) {
                    episodeCount++;

                    if (episodeCount >= maxEpisodeCount) {
                        logger.log(DEBUG, "{0} reached max episode count: {1}",
                                new String[]{ context.description, String.valueOf(maxEpisodeCount) });
                        break;
                    }
                }
            }

            if (episodeCount >= maxEpisodeCount) break;
            if (tracks.size() < 20) {
                logger.log(DEBUG, "{0} has no more pages", context.description);
                break;
            }

            page++;
        }

        context.shutdown();
        return removeInvalidEpisodes(podcast);
    }

    /**
     * Creates a podcast object populated with the given user's properties.
     * No episodes are included.
     */
    @NotNull
    private Podcast getPodcastForUser(@NotNull String username)
            throws IOException, HearThisException, URISyntaxException {

        logger.log(INFO, "Querying {0}''s info on HearThis", username);
        String url = String.format("%s%s/", API_BASE_URL, username);

        try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    throw new HearThisUserException("HearThis user not found", username);
                }
                throw new HearThisException("Failed to fetch user info: " + response.code());
            }

            String body = Objects.requireNonNull(response.body()).string();
            JsonArray jsonArray = new Gson().fromJson(body, JsonArray.class);

            if (jsonArray == null || jsonArray.isEmpty()) {
                throw new HearThisUserException("HearThis user has no data", username);
            }

            // Get user info from the first track
            JsonObject firstTrack = jsonArray.get(0).getAsJsonObject();
            JsonObject userObj = firstTrack.has("user") ? firstTrack.getAsJsonObject("user") : null;

            var podcast = new Podcast();
            podcast.userID = username;

            if (userObj != null) {
                podcast.title = getJsonString(userObj, "username", username);
                podcast.iTunesAuthorAndOwnerName = podcast.title;
                podcast.description = getJsonString(userObj, "description", "");

                String avatarUrl = getJsonString(userObj, "avatar_url", null);
                if (avatarUrl != null && !avatarUrl.isBlank()) {
                    podcast.iTunesImageUrl = new URI(avatarUrl);
                }
            } else {
                podcast.title = username;
                podcast.iTunesAuthorAndOwnerName = username;
                podcast.description = "";
            }

            podcast.link = new URI(HEARTHIS_WEB + username + "/");

            return podcast;
        }
    }

    /**
     * Fetches a JSON array from the given URL.
     */
    @Nullable
    private JsonArray fetchJsonArray(@NotNull String url, @NotNull String description) throws IOException, HearThisException {
        try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    return null;
                }
                throw new HearThisException("Failed to fetch data: " + response.code());
            }

            String body = Objects.requireNonNull(response.body()).string();
            return new Gson().fromJson(body, JsonArray.class);
        }
    }

    /**
     * Adds an episode for the given track to the given podcast.
     * Returns a boolean indicating whether an episode was added.
     */
    private boolean addEpisodeToPodcast(@NotNull JsonObject track,
                                        @NotNull Podcast podcast,
                                        @NotNull QueryContext context) {

        try {
            String trackTitle = getJsonString(track, "title", null);
            if (trackTitle == null || trackTitle.isBlank()) {
                logger.log(INFO, "Skipping track without title");
                return false;
            }

            String permalink = getJsonString(track, "permalink", null);
            String uri = getJsonString(track, "uri", null);
            if (permalink == null || uri == null) {
                logger.log(INFO, () -> "Skipping track without permalink or URI: " + trackTitle);
                return false;
            }

            String streamUrl = getJsonString(track, "stream_url", null);
            if (streamUrl == null || streamUrl.isBlank()) {
                logger.log(INFO, () -> "Skipping track without stream URL: " + trackTitle);
                return false;
            }

            PodcastEpisode episode = getPodcastEpisode(track, context);
            if (episode == null) {
                return false;
            }

            // Eliminate duplicates
            for (PodcastEpisode already : podcast.episodes) {
                if (already.enclosureUrl.equals(episode.enclosureUrl)) {
                    logger.log(DEBUG, () -> "Skipping duplicate track: " + trackTitle);
                    return false;
                }
            }

            podcast.episodes.add(episode);
            return true;
        }
        catch (Exception ex) {
            logger.log(WARNING, "Error adding episode: {0}", ex.toString());
            return false;
        }
    }

    /**
     * Creates a populated podcast episode from a HearThis track object.
     */
    @Nullable
    private PodcastEpisode getPodcastEpisode(@NotNull JsonObject track, @NotNull QueryContext context)
            throws URISyntaxException {

        PodcastEpisode episode = new PodcastEpisode();

        String title = getJsonString(track, "title", "Untitled");
        String description = getJsonString(track, "description", "");
        String permalink = getJsonString(track, "permalink", null);
        String uri = getJsonString(track, "uri", null);
        String streamUrl = getJsonString(track, "stream_url", null);

        if (permalink == null || streamUrl == null) {
            return null;
        }

        episode.title = title;
        episode.description = description;

        // Extract username from permalink (format: username/trackid)
        String[] permalinkParts = permalink.split("/");
        String username = permalinkParts.length > 0 ? permalinkParts[0] : "unknown";
        String trackId = permalinkParts.length > 1 ? permalinkParts[1] : permalink;

        // Create local URL for the file
        String localUrl = String.format("http://%s/%s/%s.mp3", localHostAndPort, username, trackId);
        episode.enclosureUrl = new URI(localUrl);

        // The stream URL is the direct download URL from HearThis
        episode.enclosureMixcloudUrl = new URI(streamUrl);

        // Set link to the track on HearThis
        episode.link = new URI(HEARTHIS_WEB + permalink + "/");

        // Parse created_at date
        String createdAt = getJsonString(track, "created_at", null);
        if (createdAt != null) {
            try {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                episode.pubDate = dateFormat.parse(createdAt);
            } catch (ParseException e) {
                logger.log(WARNING, "Failed to parse date: {0}", createdAt);
            }
        }

        // Get duration in seconds
        Integer duration = getJsonInt(track, "duration", null);
        episode.iTunesDuration = duration;

        // Get artwork URL
        String artworkUrl = getJsonString(track, "artwork_url", null);
        if (artworkUrl != null && !artworkUrl.isBlank()) {
            episode.iTunesImageUrl = new URI(artworkUrl);
        }

        // Get user info
        JsonObject userObj = track.has("user") ? track.getAsJsonObject("user") : null;
        if (userObj != null) {
            episode.iTunesAuthor = getJsonString(userObj, "username", username);
        } else {
            episode.iTunesAuthor = username;
        }

        // Check if file exists locally
        var file = new ServableFile(localUrl);
        if (file.isFile()) {
            episode.enclosureLastModified = new Date(file.lastModified());
            episode.enclosureLengthBytes = file.length();
            episode.enclosureMimeType = new MimeHelper().guessContentTypeFromName(file.getName());
        } else {
            // Fetch file metadata asynchronously
            context.getThreadPool().submit(() -> {
                try {
                    Request request = new Request.Builder().url(streamUrl).head().build();
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            String contentLength = response.header("Content-Length");
                            String contentType = response.header("Content-Type");
                            String lastModified = response.header("Last-Modified");

                            if (contentLength != null) {
                                episode.enclosureLengthBytes = Long.parseLong(contentLength);
                            }
                            if (contentType != null) {
                                episode.enclosureMimeType = contentType;
                            } else {
                                episode.enclosureMimeType = "audio/mpeg";
                            }
                            if (lastModified != null) {
                                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                                episode.enclosureLastModified = dateFormat.parse(lastModified);
                            } else {
                                episode.enclosureLastModified = new Date();
                            }
                        }
                    }
                } catch (Exception ex) {
                    logger.log(WARNING, "Failed to fetch metadata for {0}: {1}",
                            new String[] { title, ex.toString() });
                    // Set defaults
                    episode.enclosureLastModified = new Date();
                    episode.enclosureLengthBytes = 0L;
                    episode.enclosureMimeType = "audio/mpeg";
                }
            });
        }

        return episode;
    }

    /**
     * Helper method to safely get a string from a JSON object.
     */
    @Nullable
    private String getJsonString(@NotNull JsonObject obj, @NotNull String key, @Nullable String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    /**
     * Helper method to safely get an integer from a JSON object.
     */
    @Nullable
    private Integer getJsonInt(@NotNull JsonObject obj, @NotNull String key, @Nullable Integer defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    /**
     * Removes any invalid episodes from the given podcast, and returns it for convenience.
     */
    @NotNull
    private Podcast removeInvalidEpisodes(@NotNull Podcast podcast) {
        podcast.episodes.removeIf(ep -> ep.enclosureLastModified == null);
        return podcast;
    }

    /**
     * Some contextual information about a query.
     */
    @SuppressWarnings("PMD.CommentRequired")
    private static class QueryContext {
        String description;
        ExecutorService threadPool;

        @NotNull
        synchronized ExecutorService getThreadPool() {
            if (threadPool == null)
                threadPool = Executors.newCachedThreadPool();
            return threadPool;
        }

        synchronized void shutdown() throws InterruptedException, IOException {
            if (threadPool != null) {
                threadPool.shutdown();
                if (! threadPool.awaitTermination(HTTP_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS)) {
                    throw new IOException("Thread pool seems hung, what's up with that?");
                }
            }
        }
    }

    /**
     * Gets the episode_max_count configuration setting.
     * @return episode_max_count, converted to an int.
     */
    private int getEpisodeMaxCountSetting() {
        String countStr = System.getProperty("episode_max_count");
        return Integer.parseInt(countStr);
    }

    /** The local host and port from which music files will be served. */
    @NotNull
    private final String localHostAndPort;

    /** HTTP client for making API requests. */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request request = original.newBuilder()
                        .header("User-Agent", System.getProperty("user_agent", "Mixcaster"))
                        .method(original.method(), original.body())
                        .build();
                return chain.proceed(request);
            })
            .build();

    /** Timeout for HTTP connects and reads. */
    private static final int HTTP_TIMEOUT_MILLIS = 10_000;

    /** HearThis website URL. */
    private static final String HEARTHIS_WEB = "https://hearthis.at/";

    /** HearThis API base URL. */
    private static final String API_BASE_URL = "https://api-v2.hearthis.at/";
}
