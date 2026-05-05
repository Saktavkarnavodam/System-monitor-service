package ru.diplom.monitoring.model;

import java.util.function.DoublePredicate;

public enum Condition {
    GT {
        @Override public DoublePredicate test(double threshold) { return v -> v > threshold; }
    },
    GTE {
        @Override public DoublePredicate test(double threshold) { return v -> v >= threshold; }
    },
    LT {
        @Override public DoublePredicate test(double threshold) { return v -> v < threshold; }
    },
    LTE {
        @Override public DoublePredicate test(double threshold) { return v -> v <= threshold; }
    },
    EQ {
        @Override public DoublePredicate test(double threshold) { return v -> v == threshold; }
    },
    NEQ {
        @Override public DoublePredicate test(double threshold) { return v -> v != threshold; }
    };

    public abstract DoublePredicate test(double threshold);
}
