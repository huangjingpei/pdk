package com.pdk.platform.business;

import com.pdk.domain.entity.Business;

public record BusinessContext(long bizId, long appId, String bizCode, String businessName,
                              String businessDescription, String registrationMode,
                              boolean trialEnabled, int trialDurationHours,
                              int trialAccountCount, int trialCallsPerAccount,
                              boolean forceInitialPasswordChange) {
    public static BusinessContext from(Business business) {
        return new BusinessContext(
                business.getId(), business.getAppId(), business.getBizCode(), business.getBizName(),
                business.getDescription(), business.getRegistrationMode(),
                Integer.valueOf(1).equals(business.getTrialEnabled()),
                value(business.getTrialDurationHours()), value(business.getTrialAccountCount()),
                value(business.getTrialCallsPerAccount()),
                Integer.valueOf(1).equals(business.getForceInitialPasswordChange()));
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    public boolean bizIdEquals(Long value) { return value != null && bizId == value; }
}
