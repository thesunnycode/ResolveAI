package com.resolveai.platform.ai.pii;

/**
 * The check digit every payment card carries.
 *
 * <p><b>Why a card detector needs this.</b> "13 to 19 digits" describes a card number and
 * also describes an order number, an invoice reference, a UTR, a transaction id and a
 * GSTIN-adjacent string — in other words, most of the long numbers in a support ticket
 * about money. A detector without the check redacts all of them, and the model then sees
 * {@code «CARD_3»} where the customer wrote the order number it needed to classify the
 * problem. Precision here is not tidiness; it is the difference between redaction that
 * protects data and redaction that destroys meaning.
 *
 * <p>Luhn is a checksum, not a validator: it says "this could be a card", not "this is
 * one". That is the right strength for this job. A false positive that happens to pass
 * Luhn is redacted unnecessarily, which is safe; a real card that fails Luhn does not
 * exist, because the issuer computed the digit.
 */
final class Luhn {

    private Luhn() {
    }

    static boolean isValid(String digits) {
        if (digits == null || digits.length() < 13) {
            return false;
        }
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int value = c - '0';
            if (doubling) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
