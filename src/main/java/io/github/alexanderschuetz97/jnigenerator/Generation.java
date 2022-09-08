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

import java.util.HashSet;
import java.util.Set;

public class Generation {

    protected StringBuilder header = new StringBuilder();
    protected StringBuilder init = new StringBuilder();
    protected StringBuilder destroy = new StringBuilder();
    protected StringBuilder impl = new StringBuilder();
    protected Set<String> classes = new HashSet<>();

    public boolean clazz(String clazz) {
        return classes.add(clazz);
    }

    public void header(String... header) {
        for (String s : header) {
            this.header.append(s);
            this.header.append('\n');
        }
    }

    public void init(String... init) {
        for (String s : init) {
            this.init.append(s);
            this.init.append('\n');
        }
    }

    public void destroy(String... destroy) {
        for (String s : destroy) {
            this.destroy.append(s);
            this.destroy.append('\n');
        }
    }


    public void impl(String... impl) {
        for (String s : impl) {
            this.impl.append(s);
            this.impl.append('\n');
        }
    }

    public String getHeader() {
        return header.toString();
    }

    public String getInit() {
        return init.toString();
    }

    public String getImpl() {
        return impl.toString();
    }

    public String getDestroy() {
        return destroy.toString();
    }
}
