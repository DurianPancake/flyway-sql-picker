# flyway-sql-picker

一个轻量 Java 工具：读取 `sql/` 目录下的 SQL 文件，按**版本集合**或**全选**进行筛选，输出合并后的单个 SQL 文件。

## 1. 环境要求

- JDK 8+
- Maven 3.6+

支持 Windows / macOS / Linux。

## 2. 目录说明

- `src/main/java/com/flyway/sqlpicker/SqlMergeApp.java`：主程序
- `config-template.properties`：跨平台配置模板
- `sql/`：待合并 SQL 根目录（已存在）

## 3. 配置文件

先复制模板：

- Windows (PowerShell)
  - `Copy-Item config-template.properties config.properties`
- macOS / Linux
  - `cp config-template.properties config.properties`

然后修改 `config.properties`：

```properties
sql.root=./sql
db.name=mysql
selection.mode=ALL
selection.versions=
output.file=./out/merged.sql
output.includeFileHeader=true
```

### 关键参数

- `sql.root`：SQL 根目录
- `db.name`：数据库目录名（如 `mysql` / `postgresql` / `oracle` / `sqlserver`）
- `selection.mode`：
  - `ALL`：合并全部匹配 SQL
  - `VERSIONS`：按版本集合筛选
- `selection.versions`：仅在 `VERSIONS` 下生效，逗号分隔，文件名包含匹配（大小写不敏感），例如：`V10.x,V9.0SP1`
- `output.file`：输出文件路径
- `output.includeFileHeader`：是否在输出中写入每个来源文件的头注释

## 4. 构建 JAR（通用运行方式）

```bash
mvn clean package
```

构建成功后产物：`target/flyway-sql-picker-1.0.0.jar`

## 5. 运行方式

### 方式 A：默认读取当前目录 `config.properties`

```bash
java -jar target/flyway-sql-picker-1.0.0.jar
```

### 方式 B：指定配置文件路径

```bash
java -jar target/flyway-sql-picker-1.0.0.jar -c ./config.properties
```

## 6. 版本筛选示例

当你只想合并指定版本集合：

```properties
selection.mode=VERSIONS
selection.versions=V10.x,V9.0SP1
```

程序会仅合并文件名中包含上述任一版本标识的 SQL 文件。

## 7. 输出与排序说明

- 仅合并 `.sql` 文件
- 仅合并路径中包含目标 `db.name` 目录的 SQL
- 同模块内按 Flyway 文件前缀序号排序（例如 `V2__` 在 `V10__` 前）
- 输出为 UTF-8
