package com.pa.lcrdemo;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * ✅ AJOUTÉ (12 nov 2026, demande Paul — « l'écran diagnostic de connexion peut être rehaussé pour aller avec les couleurs et
 * le modèle plus moderne ») — habillage UNIQUEMENT visuel des deux écrans du diagnostic de connexion (progression des
 * 4 étapes, échec). Aucune logique : RegisterConnectionHelper garde toute sa mécanique (étapes, annulation, boutons) et ne
 * fait que passer ici les mêmes données. Couleurs Filgo identiques à l'écran simplifié (vert / bleu / ambre) ;
 * le fond et le texte suivent le thème (clair ou sombre) de la tablette.
 */
final class DiagnosticUi {

    private DiagnosticUi() {}

    static final int VERT       = Color.parseColor("#0B6B55");
    static final int VERT_PALE  = Color.parseColor("#E3F2EC");
    static final int BLEU       = Color.parseColor("#185FA5");
    static final int BLEU_PALE  = Color.parseColor("#E8F0F9");
    static final int ROUGE      = Color.parseColor("#B3261E");
    static final int ROUGE_PALE = Color.parseColor("#FDECEA");
    static final int ROUGE_TXT  = Color.parseColor("#5F1410");

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int couleurTheme(Context c, int attr, int repli) {
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
                return c.getResources().getColor(tv.resourceId, c.getTheme());
            }
        } catch (Throwable ignored) {}
        return repli;
    }

    // Même format que la carte de la flamme (SimplifiedDriverOverlay.bCarte) : fond blanc, coins 16 dp, contour bleu 2 dp,
    // titre #0B2A4A, texte #33475B — identiques quel que soit le thème de la tablette.
    private static int texteprincipal(Context c) { return Color.parseColor("#0B2A4A"); }
    private static int textesecondaire(Context c) { return Color.parseColor("#33475B"); }

    private static GradientDrawable forme(int fond, float rayonDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fond);
        g.setCornerRadius(dp(c, rayonDp));
        return g;
    }

    private static TextView texte(Context c, String t, float sp, int couleur, boolean gras) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(couleur);
        if (gras) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** Pastille d'une étape : 0 = en cours (rond bleu pâle avec roue), 1 = réussie (vert, ✓), 2 = échec (rouge, ✕). */
    private static View pastille(Context c, int etat) {
        int taille = dp(c, 28);
        if (etat == 0) {
            FrameLayout f = new FrameLayout(c);
            f.setBackground(forme(BLEU_PALE, 14, c));
            ProgressBar pb = new ProgressBar(c);
            pb.setIndeterminate(true);
            pb.setIndeterminateTintList(ColorStateList.valueOf(BLEU));
            int p = dp(c, 5);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(taille - 2 * p, taille - 2 * p);
            lp.setMargins(p, p, p, p);
            f.addView(pb, lp);
            f.setLayoutParams(new LinearLayout.LayoutParams(taille, taille));
            return f;
        }
        TextView tv = new TextView(c);
        tv.setText(etat == 1 ? "✓" : "✕");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(etat == 1 ? VERT : ROUGE);
        tv.setBackground(g);
        tv.setLayoutParams(new LinearLayout.LayoutParams(taille, taille));
        return tv;
    }

    /** Remplit la liste des étapes. idxEchec = index de l'étape en échec, ou -1 s'il n'y en a pas (la dernière non réussie est « en cours »). */
    private static void remplirEtapes(Context c, LinearLayout liste, String[] etapes, boolean[] ok, int idxEchec) {
        liste.removeAllViews();
        for (int i = 0; i < 4; i++) {
            if (etapes == null || i >= etapes.length || etapes[i] == null) break;
            int etat = ok[i] ? 1 : (i == idxEchec ? 2 : 0);
            LinearLayout ligne = new LinearLayout(c);
            ligne.setOrientation(LinearLayout.HORIZONTAL);
            ligne.setGravity(Gravity.CENTER_VERTICAL);
            ligne.setPadding(0, dp(c, 6), 0, dp(c, 6));
            ligne.addView(pastille(c, etat));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(c, 12), 0, 0, 0);
            col.addView(texte(c, "Étape " + (i + 1) + " sur 4", 11, textesecondaire(c), false));
            col.addView(texte(c, etapes[i], 14, texteprincipal(c), etat == 0));
            ligne.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            liste.addView(ligne);
        }
    }

    // ------------------------------------------------------------------ écran de progression

    static final class Progression {
        private final Context ctx;
        private final LinearLayout racine;
        private final LinearLayout liste;

        Progression(Context c) {
            ctx = c;
            racine = new LinearLayout(c);
            racine.setOrientation(LinearLayout.VERTICAL);
            racine.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 6));

            LinearLayout entete = new LinearLayout(c);
            entete.setOrientation(LinearLayout.HORIZONTAL);
            entete.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout rond = new FrameLayout(c);
            rond.setBackground(forme(BLEU_PALE, 22, c));
            ProgressBar pb = new ProgressBar(c);
            pb.setIndeterminate(true);
            pb.setIndeterminateTintList(ColorStateList.valueOf(BLEU));
            int p = dp(c, 9);
            FrameLayout.LayoutParams lpPb = new FrameLayout.LayoutParams(dp(c, 44) - 2 * p, dp(c, 44) - 2 * p);
            lpPb.setMargins(p, p, p, p);
            rond.addView(pb, lpPb);
            entete.addView(rond, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
            LinearLayout titres = new LinearLayout(c);
            titres.setOrientation(LinearLayout.VERTICAL);
            titres.setPadding(dp(c, 14), 0, 0, 0);
            titres.addView(texte(c, "Connexion au registre", 18, texteprincipal(c), true));
            titres.addView(texte(c, "Vérification du Bluetooth et du registre…", 13, textesecondaire(c), false));
            entete.addView(titres, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            racine.addView(entete);

            View filet = new View(c);
            filet.setBackgroundColor(Color.argb(40, 128, 128, 128));
            LinearLayout.LayoutParams lpF = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1));
            lpF.setMargins(0, dp(c, 16), 0, dp(c, 8));
            racine.addView(filet, lpF);

            liste = new LinearLayout(c);
            liste.setOrientation(LinearLayout.VERTICAL);
            racine.addView(liste);
        }

        View racine() { return racine; }

        void maj(String[] etapes, boolean[] ok, int idxEchec) {
            remplirEtapes(ctx, liste, etapes, ok, idxEchec);
        }
    }

    // ------------------------------------------------------------------ écran d'échec

    static View vueEchec(Context c, String[] etapes, boolean[] ok, String erreur) {
        LinearLayout racine = new LinearLayout(c);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 6));

        LinearLayout entete = new LinearLayout(c);
        entete.setOrientation(LinearLayout.HORIZONTAL);
        entete.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(c);
        badge.setText("!");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ROUGE);
        badge.setBackground(g);
        entete.addView(badge, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout titres = new LinearLayout(c);
        titres.setOrientation(LinearLayout.VERTICAL);
        titres.setPadding(dp(c, 14), 0, 0, 0);
        titres.addView(texte(c, "Registre non joignable", 18, texteprincipal(c), true));
        titres.addView(texte(c, "La connexion n’a pas pu être rétablie.", 13, textesecondaire(c), false));
        entete.addView(titres, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        racine.addView(entete);

        LinearLayout liste = new LinearLayout(c);
        liste.setOrientation(LinearLayout.VERTICAL);
        // À l'échec, une étape non réussie n'est jamais « en cours » : on les montre toutes comme échouées après la première.
        remplirEtapesEchec(c, liste, etapes, ok);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpL.setMargins(0, dp(c, 14), 0, dp(c, 10));
        racine.addView(liste, lpL);

        if (erreur != null && !erreur.trim().isEmpty()) {
            TextView boite = texte(c, erreur.trim(), 14, ROUGE_TXT, false);
            boite.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            boite.setBackground(forme(ROUGE_PALE, 12, c));
            racine.addView(boite, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return racine;
    }

    private static void remplirEtapesEchec(Context c, LinearLayout liste, String[] etapes, boolean[] ok) {
        liste.removeAllViews();
        for (int i = 0; i < 4; i++) {
            if (etapes == null || i >= etapes.length || etapes[i] == null) break;
            LinearLayout ligne = new LinearLayout(c);
            ligne.setOrientation(LinearLayout.HORIZONTAL);
            ligne.setGravity(Gravity.CENTER_VERTICAL);
            ligne.setPadding(0, dp(c, 6), 0, dp(c, 6));
            ligne.addView(pastille(c, ok[i] ? 1 : 2));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(c, 12), 0, 0, 0);
            col.addView(texte(c, "Étape " + (i + 1) + " sur 4", 11, textesecondaire(c), false));
            col.addView(texte(c, etapes[i], 14, texteprincipal(c), false));
            ligne.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            liste.addView(ligne);
        }
    }

    // ------------------------------------------------------------------ habillage de la fenêtre et des boutons

    /** À appeler juste après show() : coins arrondis, fond du thème, boutons aux couleurs Filgo. Sans effet sur la logique. */
    static void styler(AlertDialog d) {
        if (d == null) return;
        try {
            Context c = d.getContext();
            if (d.getWindow() != null) {
                GradientDrawable g = forme(Color.WHITE, 16, c);
                g.setStroke(dp(c, 2), BLEU);
                d.getWindow().setBackgroundDrawable(new InsetDrawable(g, dp(c, 10)));
            }
            int[] boutons = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE};
            for (int id : boutons) {
                Button b = d.getButton(id);
                if (b == null) continue;
                b.setAllCaps(false);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                if (id == AlertDialog.BUTTON_POSITIVE) {
                    b.setTextColor(Color.WHITE);
                    b.setTypeface(Typeface.DEFAULT_BOLD);
                    b.setBackground(forme(BLEU, 20, c));
                    b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
                } else {
                    b.setTextColor(BLEU);
                }
            }
        } catch (Throwable ignored) {}
    }
}
