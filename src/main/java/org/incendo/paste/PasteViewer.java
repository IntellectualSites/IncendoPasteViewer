/*
 *    _  __                     _
 *    | |/ /__   __ __ _  _ __  | |_  _   _  _ __ ___
 *    | ' / \ \ / // _` || '_ \ | __|| | | || '_ ` _ \
 *    | . \  \ V /| (_| || | | || |_ | |_| || | | | | |
 *    |_|\_\  \_/  \__,_||_| |_| \__| \__,_||_| |_| |_|
 *
 *    Copyright (C) 2019 Alexander Söderberg
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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import xyz.kvantum.server.api.config.CoreConfig;
import xyz.kvantum.server.api.core.Kvantum;
import xyz.kvantum.server.api.core.ServerImplementation;
import xyz.kvantum.server.api.logging.Logger;
import xyz.kvantum.server.api.request.AbstractRequest;
import xyz.kvantum.server.api.response.Header;
import xyz.kvantum.server.api.response.Response;
import xyz.kvantum.server.api.util.CollectionUtil;
import xyz.kvantum.server.api.util.RequestManager;
import xyz.kvantum.server.api.views.annotatedviews.ViewMatcher;
import xyz.kvantum.server.implementation.DefaultLogWrapper;
import xyz.kvantum.server.implementation.ServerContext;
import xyz.kvantum.server.implementation.StandaloneServer;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Launcher and main class
 */
public final class PasteViewer {

    private static final Pattern IP_PATTERN = Pattern.compile("((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)");
    private static final String[] IP_REPLACE_STRINGS = new String[] {
        "Whenever, Wherever",
        "Underneath Your Clothes",
        "She Wolf",
        "Loca",
        "Waka Waka",
        "Chantaje",
        "La Tortura",
        "Cant Remember To Forget You",
        "Did It Again",
        "Dare",
        "Clandestino",
        "Nada",
        "Ojos Así",
        "Rabiosa",
        "Try Everything"
    };

    public static void main(final String[] args) {
        // Because Java is stupid...
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        new PasteViewer();
    }

    private final JSONParser jsonParser = new JSONParser();
    private final Cache<String, JSONObject> fileCache = CacheBuilder.newBuilder().maximumSize(5L)
        .expireAfterAccess(1, TimeUnit.HOURS).build();

    private final File pasteFolder;

    private PasteViewer() {
        // Create server context
        final ServerContext serverContext = ServerContext.builder().standalone(true)
            .logWrapper(new DefaultLogWrapper())
            .coreFolder(new File("kvantum"))
            .serverSupplier(StandaloneServer::new)
            .router(RequestManager.builder().build()).build();
        final Optional<Kvantum> serverOptional = serverContext.create();
        this.pasteFolder = new File(serverContext.getCoreFolder(), "pastes");
        serverOptional.ifPresent(server -> {
            server.getRouter().scanAndAdd(this);
            server.getRouter().add(new PasteRestService(server.getCoreFolder()));
            // Otherwise shit doesn't work when running through gradle
            CoreConfig.enableInputThread = false;
            server.start();
        });
    }

    @NotNull private Paste getPaste(@NotNull final String id) {
        File file = null;
        JSONObject object = fileCache.getIfPresent(id);

        // Check if the file exists in cache, or on disk
        if (object == null && !(file = new File(pasteFolder, String.format("%s.json", id))).exists()) {
            Logger.error("Unknown paste ID requested: {}", id);
            return new Paste("", "", Collections.emptyList(), Collections.emptyList(), "");
        }

        // If we didn't fetch the object from cache, read and parse it
        if (object == null) {
            try (final Reader reader = new FileReader(file)) {
                object = (JSONObject) jsonParser.parse(reader);
                fileCache.put(id, object);
                Logger.info("Read paste{} from disk!", id);
            } catch (final Throwable throwable) {
                throwable.printStackTrace();
                Logger.error("Couldn't parse paste with ID {}", id);
                return new Paste("", "", Collections.emptyList(), Collections.emptyList(), "");
            }
        } else {
            Logger.info("Read paste {} from cache!", id);
        }

        final String time = object.getOrDefault("created", "").toString();
        final JSONObject jsonFiles = (JSONObject) object.get("files");
        final JSONArray jsonFileNames = (JSONArray) object.get("file_names");
        final Collection<String> file_targets = new ArrayList<>(), file_content = new ArrayList<>();
        int index = 0;
        boolean first = true;
        for (final Object fileName : jsonFileNames) {
            final int currentIndex = index++;

            final String language;
            if (fileName.toString().contains(".")) {
                final String[] parts = fileName.toString().split("\\.");
                final String rawLanguage = parts[parts.length - 1].toLowerCase(Locale.ENGLISH);
                if (rawLanguage.equals("yml"))  {
                    language = "yaml";
                } else if (rawLanguage.equals("log")) {
                    language = "plaintext";
                } else {
                    language = rawLanguage;
                }
            } else {
                language = "plaintext";
            }

            String filteredContent = StringEscapeUtils.escapeHtml4(jsonFiles.get(fileName.toString()).toString());

            String tempString = filteredContent;
            while (!(tempString = tempString.replaceFirst("([\\s()\\]\\[]*)((\\d+.){3}\\d+)([\\s()\\]\\[]*)",
                String.format("$1%s$4", IP_REPLACE_STRINGS[new Random().nextInt(IP_REPLACE_STRINGS.length)]))).equals(filteredContent)) {
                filteredContent = tempString;
            }

            file_targets.add(String.format("<li %s><a data-target='#content-%d'>%s</a></li>",
                first ? "class='active'" : "", currentIndex, StringEscapeUtils.escapeHtml4(fileName.toString())));
            file_content.add(String.format("<div style='max-height: 93vh' %s id='content-%d'><pre class='%s'><code class='%s'>%s</code></pre></div>",
                first ? "" : "class='content-hide'", currentIndex, language, language, filteredContent));
            if (first)  {
                first = false;
            }
        }
        return new Paste(id, time, file_targets, file_content, object.toJSONString());
    }

    @ViewMatcher(filter = "paste/view/<paste>", name = "incendo-paste-main")
    public void servePaste(final AbstractRequest request, final Response response) {
        final String pasteId = getNullable(request.get("paste"));
        final boolean raw = request.getQuery().getParameters().containsKey("raw") &&
            request.getQuery().getParameters().get("raw").equalsIgnoreCase("true");
        final Paste paste = getPaste(pasteId);
        if (raw) {
            response.setResponse(paste.getRawContent());
            response.getHeader().set(Header.HEADER_CONTENT_TYPE, Header.CONTENT_TYPE_JSON);
        } else {
            String fileContent = ServerImplementation.getImplementation().getFileSystem().getPath("/templates/paste_view.html").readFile();
            // Hacky, because I couldn't get kvantum to work :p
            fileContent = fileContent.replace("{paste_id}", paste.getId())
                .replace("{paste_time}", paste.getTime())
                .replace("{file_list}", CollectionUtil.join(paste.getFile_targets(), "\n"))
                .replace("{file_content}", CollectionUtil.join(paste.getFile_content(), "\n"));
            response.setResponse(fileContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NotNull private static String getNullable(@Nullable final Object object) {
        if (object == null) {
            return "";
        }
        return object.toString();
    }

}
