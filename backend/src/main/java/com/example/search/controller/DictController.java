package com.example.search.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** IK 远程词典接口，供 ES analysis-ik 插件远程加载扩展词典/同义词典 */
@RestController
@RequestMapping("/es/dict")
public class DictController {

    /** 词典文件所在目录 */
    @Value("${es.dict.dir:config/dict}")
    private String dictDir;

    /** 扩展词典 */
    @GetMapping("/ext")
    public ResponseEntity<byte[]> ext() {
        return serve("ext_dict.txt");
    }

    /** 同义词典 */
    @GetMapping("/synonym")
    public ResponseEntity<byte[]> synonym() {
        return serve("synonym.txt");
    }

    private ResponseEntity<byte[]> serve(String name) {
        try {
            File f = new File(dictDir, name);
            byte[] data = f.exists() ? Files.readAllBytes(f.toPath()) : new byte[0];
            long lm = f.exists() ? f.lastModified() : 0L;
            String etag = "\"" + lm + "-" + data.length + "\"";
            String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                    .withZone(ZoneId.of("GMT")).withLocale(Locale.US)
                    .format(Instant.ofEpochMilli(lm));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    .header(HttpHeaders.LAST_MODIFIED, lastModified)
                    .header(HttpHeaders.ETAG, etag)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
