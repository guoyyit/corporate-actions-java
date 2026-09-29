package com.github.guoyyit.corporateactionsjava.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionParams.SplitParams.class, name = "SPLIT"),
        @JsonSubTypes.Type(value = ActionParams.MergeParams.class, name = "MERGE"),
        @JsonSubTypes.Type(value = ActionParams.DividendParams.class, name = "DIVIDEND"),
        @JsonSubTypes.Type(value = ActionParams.RightsIssueParams.class, name = "RIGHTS_ISSUE")
})
public sealed interface ActionParams
        permits ActionParams.SplitParams, ActionParams.MergeParams,
                ActionParams.DividendParams, ActionParams.RightsIssueParams {

    ActionType type();

    record SplitParams(BigDecimal ratio) implements ActionParams {
        @Override
        public ActionType type() {
            return ActionType.SPLIT;
        }
    }

    record MergeParams(BigDecimal ratio) implements ActionParams {
        @Override
        public ActionType type() {
            return ActionType.MERGE;
        }
    }

    record DividendParams(BigDecimal perShare) implements ActionParams {
        @Override
        public ActionType type() {
            return ActionType.DIVIDEND;
        }
    }

    record RightsIssueParams(BigDecimal ratio, BigDecimal subscriptionPrice) implements ActionParams {
        @Override
        public ActionType type() {
            return ActionType.RIGHTS_ISSUE;
        }
    }
}
