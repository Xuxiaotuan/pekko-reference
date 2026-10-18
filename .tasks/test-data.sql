-- 测试数据（如果xjwtest表为空，可以先插入一些数据）
USE test;

-- 插入测试数据到xjwtest
INSERT INTO xjwtest (name) VALUES 
('张三'),
('李四'),
('王五'),
('赵六'),
('钱七'),
('孙八'),
('周九'),
('吴十');

-- 查看数据
SELECT * FROM xjwtest;

-- 确保目标表存在
CREATE TABLE IF NOT EXISTS xjwtest1 LIKE xjwtest;

-- 清空目标表（如果需要）
TRUNCATE TABLE xjwtest1;
