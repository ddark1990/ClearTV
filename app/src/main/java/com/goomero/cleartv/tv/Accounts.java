package com.goomero.cleartv.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Account entry points shared by Settings and the Pluto screen. YouTube, Tubi and Prime Video sign in with a code
 * on their native screen ({@link ServiceActivity#EXTRA_SIGNIN}); Pluto TV, which has no code sign-in, with
 * ClearTV's own form ({@link PlutoSignInActivity}). No service's web sign-in page is ever shown.
 */
public final class Accounts {
    public static final int REQ_PLUTO = 41;

    private Accounts() {}

    public static boolean signedIn(Context c, String service) {
        if (service.equals("pluto")) return pluto(c).getBoolean("signed_in", false);
        return Source.create(c, service).signedIn();
    }

    public static String label(Context c, String service) {
        if (!signedIn(c, service)) return "Signed out";
        String a = service.equals("pluto") ? pluto(c).getString("account", "") : Source.create(c, service).accountLabel();
        return a.isEmpty() ? "Signed in" : a;
    }

    /** Opens the service's sign-in: its native screen's code card, or ClearTV's Pluto TV form. */
    public static void signIn(Activity a, String service) {
        if (service.equals("pluto")) {
            a.startActivityForResult(new Intent(a, PlutoSignInActivity.class), REQ_PLUTO);
            return;
        }
        Intent i = new Intent(a, ServiceActivity.class);
        i.putExtra(ServiceActivity.EXTRA_SERVICE, service);
        i.putExtra(ServiceActivity.EXTRA_SIGNIN, true);
        a.startActivity(i);
    }

    /** Result of the Pluto sign-in form; true when signed in. */
    public static boolean onPlutoResult(Context c, Intent data) {
        return data != null && data.getBooleanExtra(PlutoSignInActivity.RESULT_DONE, false) && signedIn(c, "pluto");
    }

    /** The Pluto form signed in; {@code name} is the profile's display name. */
    static void plutoSignedIn(Context c, String name) {
        pluto(c).edit().putBoolean("signed_in", true).putString("account", name == null ? "" : name).apply();
    }

    public static void signOut(Context c, String service) {
        switch (service) {
            case "pluto":
                WebActivity.clearWebSession(new String[]{"https://pluto.tv/", "https://api.pluto.tv/"});
                pluto(c).edit().remove("signed_in").remove("account").apply();
                break;
            case "prime":
                WebActivity.clearWebSession(new String[]{PrimeSource.BASE + "/", "https://www.amazon.com/"});
                Source.create(c, service).signOut();
                break;
            default:
                Source.create(c, service).signOut();
        }
    }

    private static SharedPreferences pluto(Context c) {
        return c.getApplicationContext().getSharedPreferences("svc_pluto", Context.MODE_PRIVATE);
    }
}
