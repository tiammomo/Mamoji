"use client";

interface CategoryTagProps {
  name: string;
  icon?: string;
  color?: string;
}

export default function CategoryTag({ name, icon, color }: CategoryTagProps) {
  return (
    <span
      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-sm"
      style={{
        backgroundColor: color ? color + "15" : "var(--bg-color-card-hover)",
        color: color || "var(--text-color-2)",
        border: `1px solid ${color ? color + "30" : "var(--border-color)"}`,
      }}
    >
      {icon && <span>{icon}</span>}
      {name}
    </span>
  );
}
