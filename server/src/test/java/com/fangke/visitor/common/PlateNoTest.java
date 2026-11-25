package com.fangke.visitor.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 车牌规范化：写入与查询必须用同一口径，否则相机识别出来的车牌匹配不上。 */
class PlateNoTest {

    @Test
    @DisplayName("统一成相机口径：去空格、转大写，空值归 null")
    void normalizeMatchesCameraFormat() {
        assertEquals("苏A12345", PlateNo.normalize("苏a 12345"));
        assertEquals("苏A12345", PlateNo.normalize(" 苏A12345 "));
        assertEquals("苏A12345", PlateNo.normalize("苏A\t12345"));
        assertEquals("", PlateNo.normalize(null));
        assertNull(PlateNo.normalizeToNull("   "));
        assertNull(PlateNo.normalizeToNull(null));
        assertEquals("粤B12345D", PlateNo.normalizeToNull("粤b12345d"));
    }
}
