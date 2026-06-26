package com.example.flare_android_client.flare;

import com.example.flare_android_client.phoenix.PhoenixChannelClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import okio.ByteString;

public class FlareMessageDecoder implements PhoenixChannelClient.MessageDecoder {

    @Override
    public String decode(String text) throws Exception {
        return text;
    }

    @Override
    public String decode(ByteString bytes) throws Exception {
        byte[] data = bytes.toByteArray();

        if (data.length > 0 && data[0] == 1) { // Version 1 Binary Frame
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.get(); // skip version byte

            // 1. Read header
            int headerLen = buffer.getInt();
            byte[] headerBytes = new byte[headerLen];
            buffer.get(headerBytes);
            String headerJsonStr = new String(headerBytes, StandardCharsets.UTF_8);
            JSONObject headerJson = new JSONObject(headerJsonStr);

            // 2. Read layout
            int layoutLen = buffer.getInt();
            byte[] layoutGz = new byte[layoutLen];
            buffer.get(layoutGz);

            // 3. Read variables
            int varsLen = buffer.getInt();
            byte[] varsGz = new byte[varsLen];
            buffer.get(varsGz);

            // 4. Decompress
            String layoutStr = decompressGzip(layoutGz);
            String varsStr = decompressGzip(varsGz);

            JSONObject layoutObj = layoutStr.isEmpty() ? new JSONObject() : new JSONObject(layoutStr);
            JSONArray varsArr = varsStr.isEmpty() ? new JSONArray() : new JSONArray(varsStr);

            // 5. Reconstruct payload
            JSONObject payload = headerJson.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            payload.put("layout", layoutObj);
            payload.put("variables", varsArr);

            // 6. Build Phoenix Array
            JSONArray phxMessage = new JSONArray();
            phxMessage.put(headerJson.has("join_ref") ? headerJson.get("join_ref") : JSONObject.NULL);
            phxMessage.put(headerJson.has("ref") ? headerJson.get("ref") : JSONObject.NULL);
            phxMessage.put(headerJson.optString("topic"));
            phxMessage.put(headerJson.optString("event"));
            phxMessage.put(payload);

            return phxMessage.toString();
        }

        return bytes.utf8();
    }

    private String decompressGzip(byte[] compressed) throws java.io.IOException {
        if (compressed == null || compressed.length == 0) return "";
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}