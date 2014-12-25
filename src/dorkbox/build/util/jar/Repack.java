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
package dorkbox.build.util.jar;

public class Repack {
    private final String  name;
    private int           actionValue = 0;

    public Repack(String name, PackAction... actions) {
        this.name = name;

        for (PackAction action : actions) {
            this.actionValue |= action.getValue();
        }
    }

    /**
     * Remove an action, if it exists.
     */
    public void remove(PackAction... actions) {
       for (PackAction action : actions) {
           if (canDo(action)) {
               // undo the action.
               this.actionValue ^= action.getValue();
           }
       }
    }

    public String getName() {
        return this.name;
    }

    public boolean canDo(PackAction actionType) {
        return (this.actionValue & actionType.getBaseValue()) == actionType.getBaseValue();
    }

    public int getAction() {
        return this.actionValue;
    }

    public String getExtension() {
        int extensionIndex = this.name.lastIndexOf('.');
        if (extensionIndex > 0) {
            return this.name.substring(extensionIndex).toLowerCase();
        } else {
            return "";
        }
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.actionValue;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
        return result;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Repack other = (Repack) obj;
        if (this.actionValue != other.actionValue) {
            return false;
        }
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        return true;
    }


    @Override
    public String toString() {
        return this.name;
    }
}