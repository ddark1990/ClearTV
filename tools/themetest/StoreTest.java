import com.goomero.cleartv.theme.StoreIndex;
import com.goomero.cleartv.theme.Theme;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

/** The theme store: themes/index.json parses strictly, every listed file matches its size and SHA-256, passes the
 * theme validator with no design warnings and has the entry's id; bad indexes are refused with precise paths. */
public final class StoreTest {
    private static int fails, runs;

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0], "themes");
        List<StoreIndex.Entry> es = StoreIndex.parse(new String(Files.readAllBytes(new File(dir, "index.json").toPath()), "UTF-8"));
        check("index lists themes", !es.isEmpty());
        for (StoreIndex.Entry e : es) {
            byte[] b = Files.readAllBytes(new File(dir, e.file).toPath());
            check(e.id + " size", b.length == e.size);
            check(e.id + " sha256", hex(b).equals(e.sha256));
            Theme t = Theme.parse(new String(b, "UTF-8"));
            check(e.id + " id matches", t.id.equals(e.id));
            check(e.id + " no design warnings", t.ui == null || t.ui.warnings.isEmpty());
            byte[] p = Files.readAllBytes(new File(dir, e.preview).toPath());
            check(e.id + " preview size and sha256", p.length == e.previewSize && hex(p).equals(e.previewSha256));
        }
        String ok = "{\"id\":\"x\",\"name\":\"X\",\"author\":\"a\",\"version\":1,\"file\":\"x.json\",\"size\":10,\"sha256\":\"" + z() + "\",\"preview\":\"p/x.png\",\"previewSize\":5,\"previewSha256\":\"" + z() + "\"";
        bad("{\"format\":\"cleartv-theme-index\",\"version\":1,\"themes\":[" + ok.replace("\"x.json\"", "\"../x.json\"") + "}]}", "themes[0].file");
        bad("{\"format\":\"cleartv-theme-index\",\"version\":1,\"themes\":[" + ok + ",\"exec\":1}]}", "themes[0].exec");
        bad("{\"format\":\"cleartv-theme-index\",\"version\":1,\"themes\":[" + ok.replace("\"size\":10", "\"size\":99999999") + "}]}", "themes[0].size");
        bad("{\"format\":\"cleartv-theme-index\",\"version\":1,\"themes\":[" + ok + "}," + ok + "}]}", "themes[1].id");
        bad("{\"format\":\"cleartv-theme-index\",\"version\":1,\"themes\":[" + ok.replace("p/x.png", "p/x.svg") + "}]}", "themes[0].preview");
        bad("{\"format\":\"other\",\"version\":1,\"themes\":[]}", "format");
        System.out.println("store tests: " + (runs - fails) + "/" + runs + " passed");
        if (fails > 0) System.exit(1);
    }

    static String z() {
        return new String(new char[64]).replace('\0', 'a');
    }

    static void bad(String json, String path) {
        try {
            StoreIndex.parse(json);
            check("refused " + path, false);
        } catch (Theme.Invalid e) {
            boolean hit = false;
            for (Theme.Problem p : e.problems) hit |= p.toJson().contains("\"" + path + "\"");
            check("refused " + path, hit);
        }
    }

    static String hex(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static void check(String name, boolean ok) {
        runs++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + name);
        }
    }
}
