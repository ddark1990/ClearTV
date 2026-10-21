import com.goomero.cleartv.engine.FilterCompiler;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Build-time compiler for the bundled engine blob (app/src/main/assets/filters/engine.bin).
 * usage: CompileFilters <filters-src dir> <assets/filters dir> <out engine.bin>
 */
public class CompileFilters {
    /** Bundled filter lists, in the order the app compiles them (FilterManager.LISTS). */
    static final String[] LISTS = {"easylist.txt", "easyprivacy.txt", "ubo-filters.txt", "ubo-privacy.txt",
            "ubo-badware.txt", "ubo-quick-fixes.txt", "peter-lowe.txt", "easylist-cookie.txt", "ubo-cookies.txt",
            "app-banners.txt", "adguard-base.txt", "adguard-mobile.txt", "ubo-unbreak.txt"};
    static final Pattern HOST = Pattern.compile("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$");

    static Reader reader(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        if (f.getName().endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        final File src = new File(a[0]);
        File assets = new File(a[1]);
        File out = new File(a[2]);
        long t0 = System.currentTimeMillis();

        // DNS domain list: same rules as before (lowercase, no trailing dot, hostnames only, allowlist.txt removed)
        HashSet<String> allowed = new HashSet<>();
        try (BufferedReader br = new BufferedReader(reader(new File(assets, "allowlist.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) allowed.add(line.split("\\s+")[0].toLowerCase(Locale.ROOT));
            }
        }
        HashSet<String> domains = new HashSet<>(1 << 20);
        int skipped = 0;
        try (BufferedReader br = new BufferedReader(reader(new File(src, "ad-domains.txt.gz")), 1 << 16)) {
            String line;
            while ((line = br.readLine()) != null) {
                String d = line.trim().toLowerCase(Locale.ROOT);
                while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
                if (d.isEmpty() || d.startsWith("#")) continue;
                if (allowed.contains(d) || !HOST.matcher(d).matches()) { skipped++; continue; }
                domains.add(d);
            }
        }

        FilterCompiler c = new FilterCompiler();
        for (final String n : LISTS) {
            c.addFilterList(new FilterCompiler.Source() {
                @Override
                public Reader open() throws IOException {
                    return reader(new File(src, n));
                }
            });
        }
        // ClearTV's own rules (content allowlist) go last, after the lists, as in FilterManager.compileLists
        c.addFilterList(new FilterCompiler.Source() {
            @Override
            public Reader open() throws IOException {
                return reader(new File(assets, "app-rules.txt"));
            }
        });
        try (Reader r = reader(new File(src, "public_suffix_list.dat"))) {
            c.setPublicSuffixList(r);
        }
        c.setDomains(domains);
        try (Reader r = reader(new File(assets, "allowlist.txt"))) {
            c.addAllowlist(r);
        }
        try (Reader r = reader(new File(assets, "allowlist-app.txt"))) {
            c.addAllowlist(r);
        }
        File tmp = new File(out.getPath() + ".tmp");
        FilterCompiler.Result res;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            res = c.compile(o);
        }
        if (!tmp.renameTo(out)) throw new IOException("cannot replace " + out);
        System.out.println("DNS list: " + domains.size() + " hosts (" + skipped + " skipped)");
        System.out.println("compiled " + out + ": " + res + " in " + (System.currentTimeMillis() - t0) + " ms");
    }
}
