package com.pa.lcrdemo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * ✅ AJOUTÉ (6 nov 2026, demande Paul — « une flamme serait bien… il y a un bug et ça explose ») —
 * petite mascotte Filgo ORIGINALE : une flamme de propane avec un visage, dessinée au Canvas.
 * Aucun fichier image. Expressions : content / inquiet ; animation « explosion » : la flamme gonfle,
 * éclate en étincelles, puis se reforme inquiète.
 */
final class FlammeView extends View {

    private final Paint pExt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pInt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBlanc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPupille = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrait = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEtincelle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chemin = new Path();
    private final Path cheminInt = new Path();
    private final RectF rect = new RectF();

    private boolean inquiet = false;
    private float explosion = -1f;          // -1 = au repos, 0..1 = en cours
    private ValueAnimator anim;
    private ValueAnimator respiration;
    private float souffle = 0f;             // 0..1 : petit balancement au repos

    FlammeView(Context ctx) {
        super(ctx);
        pExt.setColor(Color.parseColor("#FF7A1A"));
        pInt.setColor(Color.parseColor("#FFC93C"));
        pBlanc.setColor(Color.WHITE);
        pPupille.setColor(Color.parseColor("#0B2A4A"));
        pTrait.setColor(Color.parseColor("#0B2A4A"));
        pTrait.setStyle(Paint.Style.STROKE);
        pTrait.setStrokeCap(Paint.Cap.ROUND);
        pEtincelle.setColor(Color.parseColor("#FFB020"));
    }

    void setInquiet(boolean v) {
        if (inquiet != v) { inquiet = v; invalidate(); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ValueAnimator.areAnimatorsEnabled()) {
            respiration = ValueAnimator.ofFloat(0f, 1f);
            respiration.setDuration(900);
            respiration.setRepeatMode(ValueAnimator.REVERSE);
            respiration.setRepeatCount(ValueAnimator.INFINITE);
            respiration.addUpdateListener(a -> { souffle = (float) a.getAnimatedValue(); if (explosion < 0f) invalidate(); });
            respiration.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (respiration != null) respiration.cancel();
        if (anim != null) anim.cancel();
    }

    /** La flamme gonfle, éclate en étincelles, puis se reforme (inquiète). */
    void exploser() {
        if (anim != null) anim.cancel();
        inquiet = true;
        if (!ValueAnimator.areAnimatorsEnabled()) { explosion = -1f; invalidate(); return; }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { explosion = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { explosion = -1f; invalidate(); }
            @Override public void onAnimationCancel(android.animation.Animator animation) { explosion = -1f; }
        });
        anim.start();
    }

    private void tracerFlamme(Path p, float cx, float base, float W, float H) {
        p.reset();
        p.moveTo(cx, base);
        p.cubicTo(cx - W * 0.55f, base, cx - W * 0.62f, base - H * 0.45f, cx - W * 0.10f, base - H * 0.70f);
        p.cubicTo(cx - W * 0.05f, base - H * 0.82f, cx + W * 0.02f, base - H * 0.92f, cx + W * 0.10f, base - H);
        p.cubicTo(cx + W * 0.12f, base - H * 0.75f, cx + W * 0.62f, base - H * 0.62f, cx + W * 0.55f, base - H * 0.28f);
        p.cubicTo(cx + W * 0.50f, base - H * 0.02f, cx + W * 0.20f, base, cx, base);
        p.close();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float base = h * 0.86f;
        float H = h * 0.62f, W = w * 0.50f;
        float echelle = 1f, alpha = 1f;
        if (explosion < 0f) {
            echelle = 1f + 0.04f * souffle;                      // respiration au repos
        } else {
            float t = explosion;
            if (t < 0.30f) { echelle = 1f + 0.55f * (t / 0.30f); }
            else if (t < 0.45f) { echelle = 1.55f; alpha = 1f - (t - 0.30f) / 0.15f; }
            else if (t < 0.55f) { echelle = 0f; alpha = 0f; }
            else {                                               // se reforme avec un petit rebond
                float u = (t - 0.55f) / 0.45f;
                echelle = (float) (1.0 - Math.pow(1 - u, 3) * Math.cos(u * 9.0) * 0.6) * Math.min(1f, u * 3f);
                alpha = Math.min(1f, u * 3f);
            }
        }
        if (alpha > 0f && echelle > 0f) {
            c.save();
            c.scale(echelle, echelle, cx, base);
            pExt.setAlpha((int) (255 * alpha));
            tracerFlamme(chemin, cx, base, W, H);
            c.drawPath(chemin, pExt);
            pInt.setAlpha((int) (255 * alpha));
            tracerFlamme(cheminInt, cx, base - H * 0.02f, W * 0.55f, H * 0.62f);
            c.drawPath(cheminInt, pInt);
            dessinerVisage(c, cx, base, W, H, alpha);
            c.restore();
        }
        if (explosion >= 0.25f) {                                // étincelles
            float t = (explosion - 0.25f) / 0.75f;
            float sortie = 1f - (1f - t) * (1f - t);
            float rMax = Math.min(w, h) * 0.46f;
            int n = 12;
            for (int i = 0; i < n; i++) {
                double ang = Math.PI * 2 * i / n + 0.2;
                float d = rMax * sortie * (i % 2 == 0 ? 1f : 0.7f);
                float x = cx + (float) Math.cos(ang) * d;
                float y = base - H * 0.45f + (float) Math.sin(ang) * d;
                pEtincelle.setAlpha((int) (255 * (1f - t)));
                pEtincelle.setColor(i % 3 == 0 ? Color.parseColor("#FFE08A") : Color.parseColor("#FF7A1A"));
                pEtincelle.setAlpha((int) (255 * (1f - t)));
                c.drawCircle(x, y, Math.max(1f, w * 0.035f * (1f - t * 0.6f)), pEtincelle);
            }
        }
    }

    private void dessinerVisage(Canvas c, float cx, float base, float W, float H, float alpha) {
        float ey = base - H * 0.36f;
        float dx = W * 0.20f;
        float r = W * 0.12f;
        pBlanc.setAlpha((int) (255 * alpha));
        pPupille.setAlpha((int) (255 * alpha));
        pTrait.setAlpha((int) (255 * alpha));
        pTrait.setStrokeWidth(Math.max(2f, W * 0.04f));
        c.drawCircle(cx - dx, ey, r, pBlanc);
        c.drawCircle(cx + dx, ey, r, pBlanc);
        float pr = r * (inquiet ? 0.42f : 0.5f);
        float oy = inquiet ? -r * 0.15f : r * 0.1f;
        c.drawCircle(cx - dx, ey + oy, pr, pPupille);
        c.drawCircle(cx + dx, ey + oy, pr, pPupille);
        if (inquiet) {                                           // sourcils inquiets + petite bouche ouverte
            c.drawLine(cx - dx - r, ey - r * 1.5f, cx - dx + r * 0.8f, ey - r * 1.15f, pTrait);
            c.drawLine(cx + dx + r, ey - r * 1.5f, cx + dx - r * 0.8f, ey - r * 1.15f, pTrait);
            rect.set(cx - W * 0.08f, ey + r * 1.5f, cx + W * 0.08f, ey + r * 2.6f);
            Paint.Style st = pPupille.getStyle();
            c.drawOval(rect, pPupille);
        } else {                                                 // sourire
            rect.set(cx - W * 0.16f, ey + r * 0.9f, cx + W * 0.16f, ey + r * 2.6f);
            c.drawArc(rect, 20f, 140f, false, pTrait);
        }
    }
}
