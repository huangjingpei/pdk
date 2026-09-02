package com.pdk.update;

import com.pdk.common.exception.BusinessException;
import com.pdk.update.service.SemanticVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticVersionTest {
    @Test void comparesNumericSegments() {
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertTrue(SemanticVersion.parse("2.0.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
    }
    @Test void rejectsNonStrictVersions() {
        for (String value : new String[]{"v1.2.3", "1.2", "1.2.3.4", "1.2.3-beta", "01.2.3"})
            assertThrows(BusinessException.class, () -> SemanticVersion.parse(value));
    }
}
