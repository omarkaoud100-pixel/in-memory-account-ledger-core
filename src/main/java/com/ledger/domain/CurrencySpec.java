package com.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A currency together with the number of minor-unit decimal places it is
 * stored and rounded to. AED = 2 places, BHD = 3 places.
 *
 * <p>Rounding mode is fixed to HALF_UP for every monetary quantization in this
 * system. See NUMBERS.md for why HALF_UP and not HALF_EVEN.
 */
public enum CurrencySpec {
    AED(2),
    BHD(3);

    /** The single rounding mode used for all monetary rounding in the ledger. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final int scale;

    CurrencySpec(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    /** Quantize a raw value to this currency's scale using HALF_UP. */
    public BigDecimal round(BigDecimal raw) {
        return raw.setScale(scale, ROUNDING);
    }

    /** Zero at this currency's scale (e.g. 0.00 for AED, 0.000 for BHD). */
    public BigDecimal zero() {
        return BigDecimal.ZERO.setScale(scale, ROUNDING);
    }
}
