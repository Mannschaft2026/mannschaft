package com.mannschaft.app.support.test.sparse;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** {@link RequiresRanchApprovedAssets} の判定本体。実判定は {@link SparseExcludedAssets}。 */
public class RanchApprovedAssetsCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        var decision = SparseExcludedAssets.decide(SparseExcludedAssets.RANCH_APPROVED_DIR);
        return decision.skip()
                ? ConditionEvaluationResult.disabled(decision.reason())
                : ConditionEvaluationResult.enabled(decision.reason());
    }
}
