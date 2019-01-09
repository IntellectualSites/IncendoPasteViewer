/*
 *    _  __                     _
 *    | |/ /__   __ __ _  _ __  | |_  _   _  _ __ ___
 *    | ' / \ \ / // _` || '_ \ | __|| | | || '_ ` _ \
 *    | . \  \ V /| (_| || | | || |_ | |_| || | | | | |
 *    |_|\_\  \_/  \__,_||_| |_| \__| \__,_||_| |_| |_|
 *
 *    Copyright (C) 2018 Alexander Söderberg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.incendo.paste;

import xyz.kvantum.server.api.core.Kvantum;
import xyz.kvantum.server.api.util.RequestManager;
import xyz.kvantum.server.implementation.DefaultLogWrapper;
import xyz.kvantum.server.implementation.ServerContext;
import xyz.kvantum.server.implementation.StandaloneServer;

import java.io.File;
import java.util.Optional;

/**
 * Launcher and main class
 */
public class PasteViewer {

    public static void main(final String[] args) {
        // Because Java is stupid...
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        // Create server context
        final ServerContext serverContext = ServerContext.builder().standalone(true)
            .logWrapper(new DefaultLogWrapper())
            .coreFolder(new File("kvantum"))
            .serverSupplier(StandaloneServer::new)
            .router(RequestManager.builder().build()).build();
        final Optional<Kvantum> serverOptional = serverContext.create();
        serverOptional.ifPresent(Kvantum::start);
    }

}
