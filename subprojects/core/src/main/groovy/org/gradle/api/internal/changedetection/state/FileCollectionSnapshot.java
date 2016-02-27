/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import org.gradle.util.ChangeListener;

import java.io.File;
import java.util.Collection;

/**
 * An immutable snapshot of the contents of a collection of files.
 */
public interface FileCollectionSnapshot {
    /**
     * Returns an iterator over the changes to file contents since the given snapshot. Ignores changes to file meta-data.
     */
    ChangeIterator<String> iterateChangesSince(FileCollectionSnapshot oldSnapshot);

    /**
     * Returns a copy of this snapshot with file meta-data updated from the given snapshot. Ignores new files that are present in the given snapshot but not this snapshot.
     */
    FileCollectionSnapshot updateFrom(FileCollectionSnapshot newSnapshot);

    Diff changesSince(FileCollectionSnapshot oldSnapshot);

    Collection<File> getFiles();

    FilesSnapshotSet getSnapshot();

    interface Diff {
        /**
         * Applies this diff to the given snapshot. Adds any added or changed files in this diff to the given snapshot.
         * Removes any removed files in this diff from the given snapshot.
         *
         * @param snapshot the snapshot to apply the changes to.
         * @return an updated copy of the provided snapshot
         */
        FileCollectionSnapshot applyTo(FileCollectionSnapshot snapshot);
    }

    interface ChangeIterator<T> {
        boolean next(ChangeListener<T> listener);
    }
}
