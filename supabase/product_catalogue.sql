-- Already applied to the "Lead Management" Supabase project (cssswtyzfmfoijcjgxpe).
-- Kept here for reference / re-creating the project.

create table public.products (
  id text primary key,
  name text not null,
  description text not null default '',
  price text not null default '',
  images jsonb not null default '[]'::jsonb check (jsonb_typeof(images) = 'array'),
  active boolean not null default true,
  sort_order bigint not null default 0,
  created_at timestamptz not null default now()
);
alter table public.products enable row level security;
create policy products_read on public.products for select to authenticated using (true);
create policy products_admin on public.products for all to authenticated
  using (public.is_admin()) with check (public.is_admin());
alter publication supabase_realtime add table public.products;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('product-images', 'product-images', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

create policy product_images_read on storage.objects for select to authenticated
  using (bucket_id = 'product-images');
create policy product_images_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'product-images' and public.is_admin());
create policy product_images_update on storage.objects for update to authenticated
  using (bucket_id = 'product-images' and public.is_admin())
  with check (bucket_id = 'product-images' and public.is_admin());
create policy product_images_delete on storage.objects for delete to authenticated
  using (bucket_id = 'product-images' and public.is_admin());
