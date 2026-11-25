-- 车牌规范化（2026-11-26 巡检）：
-- 员工档案与访客预约里的车牌统一成「相机口径」——大写、去空格/制表符。
-- 之前保存原样入库，导致 苏a 12345 这类车牌在判定时按字符串比对永远匹配不上
-- （相机本地白名单能开闸，但平台判成「未登记车辆」，记录也挂不到人）。
UPDATE employee
   SET plate_no = UPPER(REPLACE(REPLACE(plate_no, ' ', ''), CHAR(9), ''))
 WHERE plate_no IS NOT NULL
   AND plate_no <> '';

UPDATE appointment
   SET plate_no = UPPER(REPLACE(REPLACE(plate_no, ' ', ''), CHAR(9), ''))
 WHERE plate_no IS NOT NULL
   AND plate_no <> '';
