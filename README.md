# Excel数据导入系统

基于 Spring Boot + Vue 3 的Excel大数据导入系统，支持5万条数据导入及上报国家平台功能。

## 技术栈

- **Frontend**: Vue 3 + Element Plus + Tailwind CSS + Pinia
- **Backend**: Spring Boot 3.2 + MyBatis Plus + EasyExcel
- **Database**: MySQL 8.0
- **Security**: Spring Security + JWT + BCrypt加密

## 核心功能

- Excel文件上传与解析（支持5万条数据，使用EasyExcel SAX模式避免OOM）
- 数据校验与批量导入
- **数据上送国家平台（队列异步分批，不阻塞页面）**
  - 导入成功后加入上送队列，独立线程池按批发送清单到国家平台模拟接口
  - 页面实时展示：待上送、上送中、成功、失败数量
  - 某批失败后按配置"继续后续批次"或"暂停"
  - 支持暂停 / 继续 / 重试失败批次 / 取消
  - 每批请求报文、响应报文、HTTP状态码、耗时、错误全部落库，可逐批追踪
  - 服务重启自动恢复中断任务（CONTINUE自动续跑，PAUSE置为暂停待人工处理）
- 异常数据处理与导出
- 用户登录认证（密码BCrypt加密）

## 启动指南

### 1. 确保 Docker Desktop 已启动

### 2. 在根目录执行

```bash
docker compose up -d --build
```

### 3. 等待容器启动完成（首次构建约3-5分钟）

查看日志：

```bash
docker compose logs -f
```

## 服务地址

| 服务        | 地址                                  |
| ----------- | ------------------------------------- |
| Frontend    | http://localhost:3000                 |
| Backend API | http://localhost:8080                 |
| Swagger文档 | http://localhost:8080/swagger-ui.html |
| Database    | localhost:3306                        |

## 测试账号

| 用户名 | 密码     |
| ------ | -------- |
| admin  | admin123 |

## 项目结构

```
677/
├── backend/                    # Spring Boot后端
│   ├── src/main/java/com/excel/
│   │   ├── config/            # 配置类
│   │   ├── controller/        # 控制器
│   │   ├── dto/               # 数据传输对象
│   │   ├── entity/            # 实体类
│   │   ├── listener/          # EasyExcel监听器
│   │   ├── mapper/            # MyBatis Mapper
│   │   ├── service/           # 服务层
│   │   └── utils/             # 工具类
│   └── Dockerfile
├── frontend/                   # Vue 3前端
│   ├── src/
│   │   ├── api/               # API接口
│   │   ├── assets/            # 静态资源
│   │   ├── components/        # 组件
│   │   ├── router/            # 路由
│   │   ├── stores/            # Pinia状态管理
│   │   └── views/             # 页面
│   └── Dockerfile
└── docker-compose.yml          # 容器编排
```

## API接口

### 认证接口

- `POST /api/auth/login` - 用户登录

### Excel接口

- `POST /api/excel/import` - 导入Excel文件
- `GET /api/excel/records` - 获取导入记录
- `GET /api/excel/data/{batchNo}` - 获取批次数据
- `GET /api/excel/template` - 下载导入模板
- `GET /api/excel/export/errors/{batchNo}` - 导出错误数据

### 上送队列接口（推荐，异步分批不阻塞）

- `POST /api/report/jobs/{batchNo}` - 将导入批次加入上送队列（body：batchSize每批数量、failStrategy：CONTINUE/PAUSE）
- `GET /api/report/jobs` - 上送任务分页列表（可按status筛选）
- `GET /api/report/jobs/{jobId}` - 上送任务详情（含待上送/上送中/成功/失败数量）
- `GET /api/report/jobs/{jobId}/batches` - 任务下所有批次
- `GET /api/report/batches/{batchId}` - 单批追踪详情（请求/响应报文、HTTP状态、耗时）
- `POST /api/report/jobs/{jobId}/pause` - 暂停
- `POST /api/report/jobs/{jobId}/resume` - 继续
- `POST /api/report/jobs/{jobId}/retry` - 重试失败批次
- `POST /api/report/jobs/{jobId}/cancel` - 取消

### 国家平台模拟接口

- `POST /api/mock/national/report` - 国家平台批量接收模拟接口（约8%整批拒收、3%平台错误、单条约5%失败、200~800ms耗时）

### 兼容保留的旧同步上报接口

- `POST /api/excel/report/{batchNo}` - 同步上报数据（大数据量会长时间阻塞，建议改用上面的上送队列）
- `GET /api/excel/report/failed/{batchNo}` - 获取上报失败数据
- `POST /api/excel/report/retry/{batchNo}` - 重试上报

## 数据导入模板

| 字段     | 说明                | 是否必填 |
| -------- | ------------------- | -------- |
| 数据编号 | 唯一标识            | 是       |
| 姓名     | 姓名（最多50字符）  | 是       |
| 身份证号 | 18位身份证号        | 否       |
| 手机号   | 11位手机号          | 否       |
| 金额     | 数值，不能为负      | 否       |
| 地址     | 地址（最多200字符） | 否       |
| 备注     | 备注信息            | 否       |

## 注意事项

1. 系统使用EasyExcel的SAX模式解析Excel，内存占用低，支持大文件
2. 数据每1000条批量入库，保证性能
3. 上送国家平台采用队列异步分批：导入成功后加入队列，可配置每批数量与失败策略（继续/暂停），页面实时展示进度，每批请求响应均可在"上送队列"中追踪
4. 国家平台为模拟接口，会随机产生整批拒收、平台错误及单条失败用于测试异常处理
5. 密码使用BCrypt加密存储，与数据库密码加密方式一致
