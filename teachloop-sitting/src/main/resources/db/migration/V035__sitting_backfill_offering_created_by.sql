-- 历史数据修复（一次性，不是常规流程）：V030 才加上 offerings.created_by，之前的存量布置该列为空；
-- 教师列表按 created_by 过滤后，这些老布置会从所有教师的首页消失。
--
-- 回填规则：一场布置参与的班级全部属于同一个老师时，归属没有歧义，取该班级的 teacher_id 回填；
-- 参与班级跨多个老师的布置无法判断谁的，保持 NULL（下一次按归属过滤时仍然看不到，但不瞎猜）。
--
-- 注意：classes 表属于 classroom 模块。这里是跨模块的一次性数据修复，只允许出现在迁移里，
-- 不要当作常规读写方式复制到业务代码（业务代码跨模块只走端口，check-boundaries.py 会拦）。
update offerings o
   set created_by = t.teacher_id
  from (
        select oc.offering_id,
               (array_agg(distinct c.teacher_id))[1] as teacher_id
          from offering_classes oc
          join classes c on c.id = oc.class_id
         group by oc.offering_id
        having count(distinct c.teacher_id) = 1
       ) t
 where o.id = t.offering_id
   and o.created_by is null;
