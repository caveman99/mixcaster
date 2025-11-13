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

package jakshin.mixcaster.mixcloud;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.util.List;
import java.util.Locale;

/**
 * A "set" of music files, defined by four bits of info: a music source,
 * a user, a music type (stream, uploads, etc.), and playlist slug if applicable.
 *
 * @param source    The music source (mixcloud or hearthis).
 * @param username  A user's username. If this is empty, the music set isn't valid.
 * @param musicType A music type (stream, shows, favorites, history, or playlist).
 *                  If this is empty, MixcloudClient's queryDefaultView() should be used.
 * @param playlist  A playlist's slug (the last element of its URL).
 *                  This should empty unless musicType is "playlist", in which case it's required.
 */
public record MusicSet(@NotNull String source, @NotNull String username, @Nullable String musicType, @Nullable String playlist) {
    /**
     * Creates a new instance.
     */
    public MusicSet {
        if (username.endsWith("'s") || username.endsWith("'s") || username.endsWith("'s")) {
            // un-possessive the username
            username = username.substring(0, username.length() - 2);
        }

        if (username.isBlank()) {
            throw new InvalidInputException("Empty username");
        }

        if (source == null || source.isBlank()) {
            throw new InvalidInputException("Empty source");
        }

        // Normalize source
        source = source.toLowerCase(Locale.ROOT);
        if (!source.equals("mixcloud") && !source.equals("hearthis")) {
            throw new InvalidInputException("Invalid source: " + source + " (must be 'mixcloud' or 'hearthis')");
        }

        if (musicType != null) {
            musicType = switch (musicType) { //NOPMD - suppressed UseStringBufferForStringAppends - WTF PMD?
                case "stream", "shows", "favorites", "history", "playlist" -> musicType;
                case "uploads" -> "shows";
                case "listens" -> "history";
                case "playlists" -> "playlist";
                case "tracks" -> "shows";  // HearThis uses "tracks" instead of "shows"
                case "likes" -> "favorites";  // HearThis uses "likes" instead of "favorites"
                default -> throw new InvalidInputException("Invalid music type: " + musicType);
            };
        }

        if ("playlist".equals(musicType)) {
            if (playlist == null || playlist.isBlank())
                throw new InvalidInputException("Missing a playlist slug");
        }
        else if (playlist != null) {
            String msg = String.format("Extra argument with music type '%s': %s", musicType, playlist);
            throw new InvalidInputException(msg);
        }
    }

    /**
     * Creates a new instance.
     * @param input Input that defines the music set (username, music type, playlist).
     *              Can optionally start with "hearthis" or "mixcloud" to specify source.
     */
    @Contract("_ -> new")
    @NotNull
    public static MusicSet of(@NotNull final List<String> input) throws InvalidInputException {
        // Check if the first element is a source name
        String source = "mixcloud";  // default to Mixcloud for backwards compatibility
        List<String> actualInput = input;

        if (!input.isEmpty()) {
            String firstWord = input.get(0).toLowerCase(Locale.ROOT);
            if (firstWord.equals("hearthis") || firstWord.equals("mixcloud")) {
                source = firstWord;
                actualInput = input.subList(1, input.size());
            }
        }

        return of(actualInput, source);
    }

    /**
     * Creates a new instance with explicit source.
     * @param input Input that defines the music set (username, music type, playlist).
     * @param defaultSource The default source to use if not specified in the URL.
     */
    @Contract("_, _ -> new")
    @NotNull
    public static MusicSet of(@NotNull final List<String> input, @NotNull String defaultSource) throws InvalidInputException {
        if (input.isEmpty() || input.size() > 3) {
            throw new InvalidInputException("Wrong number of arguments: " + input.size());
        }

        final String mixcloudSite = "https://www.mixcloud.com/";  // with trailing slash, so we strip it below
        final String hearthisSite = "https://hearthis.at/";  // with trailing slash, so we strip it below

        String source = defaultSource;
        String firstArg = input.get(0);

        if (input.size() == 1 && firstArg.startsWith(mixcloudSite)) {
            // split the URL into its parts, and recurse
            String path = firstArg.substring(mixcloudSite.length());
            String[] pathParts = path.split("/");  // trailing empty string not included
            return of(List.of(pathParts), "mixcloud");
        }

        if (input.size() == 1 && firstArg.startsWith(hearthisSite)) {
            // split the URL into its parts, and recurse
            String path = firstArg.substring(hearthisSite.length());
            String[] pathParts = path.split("/");  // trailing empty string not included
            return of(List.of(pathParts), "hearthis");
        }

        String username = input.get(0);
        String musicType = (input.size() > 1) ? input.get(1).toLowerCase(Locale.ROOT) : null;
        String playlist = (input.size() > 2) ? input.get(2) : null;

        return new MusicSet(source, username, musicType, playlist);
    }

    /**
     * Returns a string representation of the music set.
     * If you split the returned string on whitespace,
     * you can pass the pieces to of() to create an equivalent music set.
     */
    @NotNull
    @Override
    public String toString() {
        String sourcePrefix = source.equals("hearthis") ? "[HearThis] " : "";
        if (musicType == null)
            return sourcePrefix + username;  // a user's default view
        else if (playlist == null)
            return String.format("%s%s's %s", sourcePrefix, username, musicType);
        else
            return String.format("%s%s's playlist %s", sourcePrefix, username, playlist);
    }

    /**
     * Oh noes, we ran into a problem constructing a music set.
     */
    public static class InvalidInputException extends RuntimeException {
        /**
         * Constructs a new exception with the specified detail message.
         * @param message The detail message (which is saved for later retrieval by the Throwable.getMessage() method).
         */
        InvalidInputException(String message) {
            super(message);
        }

        /** Serialization version number.
            This should be updated whenever the class definition changes. */
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
