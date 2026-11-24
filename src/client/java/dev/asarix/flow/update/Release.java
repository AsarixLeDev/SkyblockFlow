package dev.asarix.flow.update;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

record Release(String version, boolean beta, String title, List<Change> changes,
               URI jar, String digest, long bytes, URI details) {
    record Change(String type, String summary, String author, String detail) {}
    static final String REPO = "AsarixLeDev/SkyblockFlow";
    static final String PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final Pattern TAG = Pattern.compile("SkyblockFlow-(?:PUBLIC-(\\d+\\.\\d+)|(\\d+\\.\\d+\\.[1-9]\\d*)-beta)");
    private static final Pattern BULLET = Pattern.compile("^- \\[(Feature|Fix|Improvement|Performance|Refactor)] (.+) \\(([^()]+)\\)$");

    static List<Change> notes(String body) {
        var list = new ArrayList<Change>();
        for (String line : body.lines().toList()) {
            var m = BULLET.matcher(line.strip());
            if (m.matches()) list.add(new Change(m.group(1), m.group(2), m.group(3), ""));
        }
        return List.copyOf(list);
    }

    Release detailed(String text) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("Format: 1\n")) return this;
        var result = new ArrayList<>(changes);
        var records = Pattern.compile("(?m)^\\[[a-z0-9-]+]\\n").split(normalized);
        int i = 0;
        for (String record : Arrays.copyOfRange(records, 1, records.length)) {
            if (i >= result.size()) break;
            var item = result.get(i++);
            String expected = "Type: "+item.type+"\nAuthor: "+item.author+"\nSummary: "+item.summary+"\nDetails:";
            if (record.startsWith(expected)) result.set(i-1, new Change(item.type,item.summary,item.author,
                    record.substring(expected.length()).strip().replaceAll("\\n +"," ")));
        }
        return new Release(version,beta,title,List.copyOf(result),jar,digest,bytes,details);
    }

    static Optional<Release> parse(JsonObject release) {
        return parse(release,"SkyblockFlow",REPO);
    }
    static Optional<Release> parse(JsonObject release,String name,String repository) {
        if (release.get("draft").getAsBoolean()) return Optional.empty();
        String tag = release.get("tag_name").getAsString();
        var m = TAG.matcher(tag.startsWith(name+"-")?"SkyblockFlow"+tag.substring(name.length()):"");
        if (!m.matches()) return Optional.empty();
        boolean beta = m.group(2) != null;
        if (beta != release.get("prerelease").getAsBoolean()) return Optional.empty();
        String version = beta ? m.group(2)+"-beta" : m.group(1);
        String jarName = name.toLowerCase(Locale.ROOT)+"-"+version+".jar";
        URI jar = null, details = null;
        String hash = "";
        long bytes = 0;
        for (var entry : release.getAsJsonArray("assets")) {
            var asset = entry.getAsJsonObject();
            String assetName = asset.get("name").getAsString();
            String url = asset.get("browser_download_url").getAsString();
            if (!url.equals("https://github.com/"+repository+"/releases/download/"+tag+"/"+assetName)) continue;
            if (assetName.equals("CHANGE_DETAILS.txt")) details = URI.create(url);
            if (assetName.equals(jarName)) {
                jar = URI.create(url);
                bytes = asset.get("size").getAsLong();
                var digest = asset.get("digest");
                if (digest != null && !digest.isJsonNull()) hash = digest.getAsString().replaceFirst("^sha256:","");
            }
        }
        if (jar == null || bytes <= 0 || bytes > 128L*1024*1024 || !hash.matches("[0-9a-f]{64}")) return Optional.empty();
        return Optional.of(new Release(version,beta,name+" "+version.replace("-beta"," Beta"),
                notes(release.get("body").getAsString()),jar,hash,bytes,details));
    }

    static int compare(String a, String b) {
        int[] left = numbers(a), right = numbers(b);
        for (int i=0;i<3;i++) {
            int c = Integer.compare(left[i],right[i]);
            if (c!=0) return c;
        }
        return Integer.compare(left[3],right[3]);
    }

    private static int[] numbers(String version) {
        var m = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (!m.find()) return new int[4];
        return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),
                m.group(3)==null ? 0 : Integer.parseInt(m.group(3)),version.endsWith("-dev")?0:1};
    }
}
