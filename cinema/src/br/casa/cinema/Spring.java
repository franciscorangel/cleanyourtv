package br.casa.cinema;

import android.animation.TimeInterpolator;

/**
 * Critically damped spring (damping ratio 1.0, no overshoot) as an interpolator, the default Apple uses for UI that
 * just moves: x(t) = 1 - (1 + wt)e^(-wt), normalised so it ends exactly at 1. ViewPropertyAnimator starts from the
 * current on-screen value, so a new focus move mid-flight re-targets without a jump.
 */
final class Spring implements TimeInterpolator {
    static final Spring UI = new Spring(6.5f);
    /** The three durations used across Home: small state changes, focus and text, large moves. */
    static final long FAST = 200, BASE = 300, SLOW = 420;

    private final float omega;
    private final float end;

    Spring(float omega) {
        this.omega = omega;
        this.end = raw(1f);
    }

    private float raw(float t) {
        float wt = omega * t;
        return (float) (1 - (1 + wt) * Math.exp(-wt));
    }

    @Override public float getInterpolation(float t) { return raw(t) / end; }
}
