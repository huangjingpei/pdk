package com.pdk.update.service;

import com.pdk.common.exception.BusinessException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {
    private static final Pattern STRICT = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");

    public static SemanticVersion parse(String value) {
        Matcher matcher = STRICT.matcher(value == null ? "" : value.trim());
        if (!matcher.matches()) throw new BusinessException(42290, "版本必须是严格的 MAJOR.MINOR.PATCH");
        try {
            return new SemanticVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException e) {
            throw new BusinessException(42290, "版本号数值超出支持范围");
        }
    }

    @Override public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        return result;
    }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
