package com.resolveai.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Scoring, as a pure function over predictions.
 *
 * <h2>Why macro-F1 and not accuracy</h2>
 *
 * <p>Accuracy is the headline number and it is the one that lies. The ticket corpus is
 * not balanced — {@code PAYMENT} is roughly a quarter of it — so a classifier that
 * answers {@code PAYMENT} for everything scores about 25% and looks merely poor, while a
 * classifier that is excellent on {@code PAYMENT} and useless on {@code ONBOARDING}
 * scores well and routes every onboarding ticket to the wrong team. Neither failure is
 * visible in one number.
 *
 * <p><b>Macro-F1 averages the per-class F1 scores with equal weight</b>, so a category
 * the model cannot do drags the score down however rare it is. That is the right
 * weighting here, because the cost of a misroute is borne by the customer in the small
 * category, not by the average.
 *
 * <p>Both are reported, along with the per-category breakdown, because the breakdown is
 * what you actually act on: "the gate went red" is a signal, "{@code BILLING} recall
 * fell from 0.9 to 0.4 and the predictions went to {@code PAYMENT}" is a diagnosis. The
 * two categories overlapping is a known property of this domain — the prompt says to
 * choose by whose money is at stake — so that particular confusion is the first thing
 * worth looking at.
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /**
     * @param expected  the label
     * @param actual    what the model said, or {@code null} when it failed to answer.
     *                  <b>A failure counts as wrong, not as absent.</b> Dropping
     *                  unanswered cases would let a prompt that crashes on hard inputs
     *                  score better than one that answers them badly.
     */
    public record Prediction(String caseName, String expected, String actual) {

        public boolean correct() {
            return expected != null && expected.equals(actual);
        }
    }

    /** Everything a run reports, ready to be written to {@code eval_run.metrics}. */
    public record Scores(int total, int correct, double accuracy, double macroF1,
                         Map<String, CategoryScore> perCategory) {
    }

    /**
     * @param support how many cases carried this label. Reported because an F1 computed
     *                over three examples is not evidence, and a reader needs to know
     *                which numbers to trust.
     */
    public record CategoryScore(int support, int truePositives, int falsePositives,
                                int falseNegatives, double precision, double recall,
                                double f1) {
    }

    public static Scores score(List<Prediction> predictions) {
        // Every label that appears as an expectation OR as a prediction. Including
        // predicted-but-never-expected labels is what surfaces a model that has started
        // inventing a category, which would otherwise show only as a dip in recall
        // somewhere else.
        TreeSet<String> labels = new TreeSet<>();
        for (Prediction p : predictions) {
            if (p.expected() != null) {
                labels.add(p.expected());
            }
            if (p.actual() != null) {
                labels.add(p.actual());
            }
        }

        Map<String, CategoryScore> perCategory = new LinkedHashMap<>();
        double f1Sum = 0;
        int scoredLabels = 0;

        for (String label : labels) {
            int truePositives = 0;
            int falsePositives = 0;
            int falseNegatives = 0;
            int support = 0;
            for (Prediction p : predictions) {
                boolean expectedThis = label.equals(p.expected());
                boolean predictedThis = label.equals(p.actual());
                if (expectedThis) {
                    support++;
                }
                if (expectedThis && predictedThis) {
                    truePositives++;
                } else if (!expectedThis && predictedThis) {
                    falsePositives++;
                } else if (expectedThis) {
                    falseNegatives++;
                }
            }
            double precision = safeDivide(truePositives, truePositives + falsePositives);
            double recall = safeDivide(truePositives, truePositives + falseNegatives);
            double f1 = precision + recall == 0 ? 0
                    : 2 * precision * recall / (precision + recall);
            perCategory.put(label, new CategoryScore(support, truePositives, falsePositives,
                    falseNegatives, round(precision), round(recall), round(f1)));

            // Only labels that actually occur in the gold set contribute to the macro
            // average. A hallucinated label scores 0 and would otherwise let the model
            // lower its own macro-F1 by inventing categories, which double-counts a
            // failure already paid for in the real labels' precision.
            if (support > 0) {
                f1Sum += f1;
                scoredLabels++;
            }
        }

        int correct = (int) predictions.stream().filter(Prediction::correct).count();
        return new Scores(predictions.size(), correct,
                round(safeDivide(correct, predictions.size())),
                round(scoredLabels == 0 ? 0 : f1Sum / scoredLabels),
                perCategory);
    }

    private static double safeDivide(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    /** Three decimals: enough to see a real change, few enough not to chase noise. */
    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
