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
package org.incendo.paste.rest;

import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import xyz.kvantum.server.api.matching.ViewPattern;
import xyz.kvantum.server.api.request.AbstractRequest;
import xyz.kvantum.server.api.request.HttpMethod;
import xyz.kvantum.server.api.request.post.JsonPostRequest;
import xyz.kvantum.server.api.request.post.PostRequest;
import xyz.kvantum.server.api.util.MapBuilder;
import xyz.kvantum.server.api.views.rest.RestResponse;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class UploadService extends RestResponse {

    private static final JSONObject REQUEST_OF_WRONG_TYPE =
        new JSONObject(MapBuilder.<String, Object>newHashMap().put("response", "request must be encoded using JSON").get());
    private static final JSONObject REQUEST_MISSING_FILE_LIST =
        new JSONObject(MapBuilder.<String, Object>newHashMap().put("response", "request must contain a file list").get());
    private static final JSONObject REQUEST_MISSING_APPLICATION =
        new JSONObject(MapBuilder.<String, Object>newHashMap().put("response", "request must contain a valid application reference").get());
    private static final JSONObject REQUEST_FAILED_TO_STORE =
        new JSONObject(MapBuilder.<String, Object>newHashMap().put("response", "failed to store paste").get());

    private static final Map<String, Long> WRITE_THROTTLE = new ConcurrentHashMap<>();
    private static final long WRITE_THROTTLE_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    private static final Collection<String>
        VALID_APPLICATIONS = Arrays.asList("plotsquared", "fastasyncworldedit", "incendopermissions", "kvantum");

    @NotNull private static JSONObject missingFileContent(@NotNull final String fileName) {
        return new JSONObject(MapBuilder.<String, Object>newHashMap().put("response",
            String.format("Missing file content for file %s", fileName)).get());
    }

    private final File pasteFolder;

    public UploadService(@NotNull final File pasteFolder) {
        super(HttpMethod.POST, new ViewPattern("paste/paste/upload"));
        this.pasteFolder = pasteFolder;
        if (!pasteFolder.exists() && !pasteFolder.mkdir()) {
            throw new IllegalArgumentException(String.format("Failed to create paste folder %s", pasteFolder.getName()));
        }
    }

    @Override public JSONObject generate(@Nonnull AbstractRequest abstractRequest) {
        if (WRITE_THROTTLE.containsKey(abstractRequest.getSocket().getIP())) {
            final long lastWrite = WRITE_THROTTLE.get(abstractRequest.getSocket().getIP());
            if ((System.currentTimeMillis() - lastWrite) < WRITE_THROTTLE_TIMEOUT) {
                final long newWrite = WRITE_THROTTLE_TIMEOUT - ((System.currentTimeMillis()) - lastWrite);
                final long newWriteMinutes = TimeUnit.MILLISECONDS.toMinutes(newWrite);
                return new JSONObject(MapBuilder.<String, Object>newHashMap().put("response",
                    String.format("you need to wait %d minutes before creating a new paste", newWriteMinutes)).get());
            }
        }
        WRITE_THROTTLE.put(abstractRequest.getSocket().getIP(), System.currentTimeMillis());

        final PostRequest request = abstractRequest.getPostRequest();
        if (!(request instanceof JsonPostRequest)) {
            return REQUEST_OF_WRONG_TYPE;
        }
        final JsonPostRequest jsonPostRequest = (JsonPostRequest) request;
        if (!jsonPostRequest.contains("files") || jsonPostRequest.get("files") == null) {
            return REQUEST_MISSING_FILE_LIST;
        }
        if (!jsonPostRequest.contains("paste_application") || jsonPostRequest.get("paste_application") == null) {
            return REQUEST_MISSING_APPLICATION;
        }
        final String applicationId = jsonPostRequest.get("paste_application")
            .toLowerCase(Locale.ENGLISH);
        if (!VALID_APPLICATIONS.contains(applicationId)) {
            return REQUEST_MISSING_APPLICATION;
        }
        final String[] files = jsonPostRequest.get("files").split(",");
        final Map<String, String> fileMap = MapBuilder.<String, String>newHashMap().get();
        final JSONArray fileNames = new JSONArray();
        for (final String file : files) {
            final String fileName = String.format("file-%s", file);
            if (!jsonPostRequest.contains(fileName)) {
                return missingFileContent(file);
            }
            fileMap.put(file, jsonPostRequest.get(fileName));
            fileNames.add(file);
        }

        final JSONObject createdObject = new JSONObject();
        createdObject.put("files", fileMap);
        createdObject.put("file_names", fileNames);
        createdObject.put("timestamp", System.currentTimeMillis());
        createdObject.put("application_id", applicationId);

        final String pasteId = UUID.randomUUID().toString().replaceAll("-", "");
        // Store paste to database
        final String rawString = createdObject.toJSONString();
        final File file = new File(pasteFolder, String.format("%s.json", pasteId));
        boolean created;
        try {
            created = file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            created = false;
        }
        if (!created) {
            return REQUEST_FAILED_TO_STORE;
        }
        if (!file.canWrite()) {
            if (!file.setWritable(true)) {
                return REQUEST_FAILED_TO_STORE;
            }
        }
        try {
            Files.write(file.toPath(), rawString.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
            return REQUEST_FAILED_TO_STORE;
        }

        // Return response
        final JSONObject response = new JSONObject();
        response.put("created", createdObject);
        response.put("paste_id", pasteId);
        response.put("response", String.format("the paste can be viewed at https://incendo.org/paste/view/%s",
            pasteId));
        return response;
    }

}
