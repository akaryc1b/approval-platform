-- RuoYi-Vue-Plus 5.X MySQL menu example.
-- Review all identifiers and the target URL before execution.
-- The 5.X schema uses numeric is_frame/is_cache values: 0=yes/cache, 1=no/no-cache.

SET @approval_menu_id = 900000001;
SET @approval_role_id = 1;
SET @approval_web_url = 'https://approval.example.com/';

INSERT INTO sys_menu (
    menu_id,
    menu_name,
    parent_id,
    order_num,
    path,
    component,
    query_param,
    is_frame,
    is_cache,
    menu_type,
    visible,
    status,
    perms,
    icon,
    create_dept,
    create_by,
    create_time,
    update_by,
    update_time,
    remark
)
SELECT
    @approval_menu_id,
    '审批中心',
    0,
    6,
    @approval_web_url,
    NULL,
    '',
    0,
    1,
    'M',
    '0',
    '0',
    '',
    'approval',
    NULL,
    1,
    CURRENT_TIMESTAMP,
    NULL,
    NULL,
    '独立部署的 Approval Platform 外链入口'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id = @approval_menu_id
);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT @approval_role_id, @approval_menu_id
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_menu
    WHERE role_id = @approval_role_id
      AND menu_id = @approval_menu_id
);

-- To change the target after installation:
-- UPDATE sys_menu SET path = 'https://new-approval.example.com/'
-- WHERE menu_id = @approval_menu_id;
