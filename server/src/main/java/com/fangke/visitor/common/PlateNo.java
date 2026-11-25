package com.fangke.visitor.common;

/**
 * 车牌规范化（唯一口径）。
 *
 * <p>相机识别出来的车牌是大写、无空格；如果员工档案/访客预约里存的是 {@code 苏a 12345}，
 * 数据库按字符串比对就永远匹配不上——相机会开闸，但平台会判成「未登记车辆」，
 * 通行记录也就挂不到人头上。所以**写入时就统一成相机口径**，不要只在查询时规范化。
 */
public final class PlateNo {

    private PlateNo() {
    }

    /** 去空格/制表符、去首尾空白、转大写；空值返回空串。 */
    public static String normalize(String plateNo) {
        if (plateNo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(plateNo.length());
        for (int i = 0; i < plateNo.length(); i++) {
            char c = plateNo.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            // 车牌只会由汉字/字母/数字和个别分隔符组成。引号、反斜杠、花括号、控制字符一律丢掉：
            // 车牌是直接拼进下发报文 JSON 里的，留着会把报文拼坏（相机整条丢弃）。
            if (Character.isLetterOrDigit(c) || c == '·' || c == '•' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase();
    }

    /** 规范化后为空则返回 null，便于写库（列可空）。 */
    public static String normalizeToNull(String plateNo) {
        String value = normalize(plateNo);
        return value.isEmpty() ? null : value;
    }

    /** 车牌最大长度（含新能源 8 位，留出余量）。 */
    public static final int MAX_LENGTH = 16;
}
