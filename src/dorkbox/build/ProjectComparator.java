/*
 * Copyright 2012 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.build;

import java.util.Comparator;

class ProjectComparator implements Comparator<Project> {
    @Override
    public
    int compare(Project o1, Project o2) {
        // empty projects are first
        int size = o1.dependencies.size();
        int size1 = o2.dependencies.size();

        int compare = (size < size1) ? -1 : ((size == size1) ? 0 : 1);
        if (compare == 0) {
            return o1.name.compareTo(o2.name);
        }
        else {
            return compare;
        }
    }
}
