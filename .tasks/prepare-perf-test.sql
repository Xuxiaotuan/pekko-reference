-- MySQL性能测试准备脚本
-- 创建性能测试表

USE test;

-- 删除旧表
DROP TABLE IF EXISTS perf_test_source;
DROP TABLE IF EXISTS perf_test_sink;

-- 创建测试源表（用于读取测试）
CREATE TABLE perf_test_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    age INT,
    score DECIMAL(5,2),
    status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_name (name),
    INDEX idx_email (email),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 创建测试目标表（用于写入测试）
CREATE TABLE perf_test_sink (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    age INT,
    score DECIMAL(5,2),
    status VARCHAR(20),
    level VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_name (name),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入10万条测试数据到源表
DELIMITER $$
CREATE PROCEDURE generate_test_data(IN record_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    
    WHILE i <= record_count DO
        INSERT INTO perf_test_source (name, email, age, score, status)
        VALUES (
            CONCAT('用户_', i),
            CONCAT('user', i, '@example.com'),
            20 + (i % 50),
            ROUND(60 + RAND() * 40, 2),
            IF(i % 3 = 0, 'active', IF(i % 3 = 1, 'inactive', 'pending'))
        );
        
        SET i = i + 1;
        
        -- 每1000条提交一次
        IF i % 1000 = 0 THEN
            COMMIT;
        END IF;
    END WHILE;
    
    COMMIT;
END$$
DELIMITER ;

-- 生成10万条测试数据
CALL generate_test_data(100000);

-- 查看数据
SELECT COUNT(*) AS total_records FROM perf_test_source;
SELECT * FROM perf_test_source LIMIT 5;

-- 清理存储过程
DROP PROCEDURE IF EXISTS generate_test_data;
