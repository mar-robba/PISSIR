import React from 'react';

type BadgeType = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

interface BadgeProps {
  children: React.ReactNode;
  type?: BadgeType;
  className?: string;
}

export default function Badge({ children, type = 'neutral', className = '' }: BadgeProps) {
  return (
    <span className={`badge badge-${type} ${className}`}>
      {children}
    </span>
  );
}
