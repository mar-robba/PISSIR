import React from 'react';

type BadgeType = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

interface BadgeProps {
  children: React.ReactNode;
  type?: BadgeType;
  className?: string;
  /** Testo che compare passandoci sopra col mouse. */
  title?: string;
}

export default function Badge({ children, type = 'neutral', className = '', title }: BadgeProps) {
  return (
    <span className={`badge badge-${type} ${className}`} title={title}>
      {children}
    </span>
  );
}
