//
// Copyright Alexander Schütz, 2022
//
// This file is part of jnigenerator.
//
// jnigenerator is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// jnigenerator is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// A copy of the GNU General Public License should be provided
// in the COPYING files in top level directory of jnigenerator.
// If not, see <https://www.gnu.org/licenses/>.
//
package io.github.alexanderschuetz97.jnigenerator;

public class Member {
    private String classname;
    private String[] filters;

    private boolean onlyPublic = false;

    public boolean isOnlyPublic() {
        return onlyPublic;
    }

    public void setOnlyPublic(boolean onlyPublic) {
        this.onlyPublic = onlyPublic;
    }

    public String getClassname() {
        return classname;
    }

    public void setClassname(String classname) {
        this.classname = classname;
    }

    public String[] getFilters() {
        return filters;
    }

    public void setFilters(String[] filter) {
        this.filters = filter;
    }

    public boolean containsFilter(String probe) {
        if (filters != null) {
            for (String f : filters) {
                if (probe.equals(f)) {
                    return true;
                }
            }
        }

        return false;
    }
}
