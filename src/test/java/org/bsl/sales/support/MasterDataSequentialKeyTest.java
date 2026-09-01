package org.bsl.sales.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MasterDataSequentialKeyTest {

    @Test
    void formatsBomNumberWithFourMinimumDigits() {
        assertEquals("BOM0001", MasterDataSequentialKey.format("BOM", 1, 4));
        assertEquals("BOM9999", MasterDataSequentialKey.format("BOM", 9999, 4));
        assertEquals("BOM10000", MasterDataSequentialKey.format("BOM", 10000, 4));
    }

    @Test
    void findsHighestExistingBomNumberForOrderBootstrap() {
        assertEquals(27L, MasterDataSequentialKey.maxNumber(
                List.of("BOM0001", "bom0027", "LEGACY", "BOM-0028"),
                "BOM"
        ));
    }
}
