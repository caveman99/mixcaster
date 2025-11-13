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

import java.io.Serial;

/**
 * An exception thrown when something goes wrong while querying HearThis.at's API.
 */
public class HearThisException extends Exception {
    /**
     * Constructs a new exception with the specified detail message.
     * @param message The detail message (which is saved for later retrieval by the Throwable.getMessage() method).
     */
    public HearThisException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     * @param message The detail message (which is saved for later retrieval by the Throwable.getMessage() method).
     * @param cause The cause (which is saved for later retrieval by the Throwable.getCause() method).
     */
    public HearThisException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Serialization version number.
        This should be updated whenever the class definition changes. */
    @Serial
    private static final long serialVersionUID = 1L;
}
