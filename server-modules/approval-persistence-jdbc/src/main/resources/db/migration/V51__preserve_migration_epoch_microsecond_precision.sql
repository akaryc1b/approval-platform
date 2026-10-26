-- Preserve numeric JSON and existing immutable payload guards while matching JDBC rounding.
create or replace function ap_migration_json_instant_v37(payload jsonb, field_name text)
returns timestamptz language plpgsql immutable as $$
declare value jsonb; raw_value text; epoch_value numeric; whole_seconds numeric;
begin
 value:=payload->field_name;
 if value is null or value='null'::jsonb then return null; end if;
 raw_value:=value#>>'{}';
 if jsonb_typeof(value)='number' then
  epoch_value:=raw_value::numeric;
  whole_seconds:=floor(epoch_value);
  -- Round the positive fractional second before conversion to double precision.
  -- floor also preserves pre-epoch timestamps and permits carry into the next second.
  return to_timestamp(whole_seconds::double precision)
   + floor((epoch_value-whole_seconds)*1000000+0.5)::double precision * interval '1 microsecond';
 end if;
 if jsonb_typeof(value)='string' then return raw_value::timestamptz; end if;
 raise exception using errcode='23514',message='migration timestamp payload must be string number or null';
end $$;
