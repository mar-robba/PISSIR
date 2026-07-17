import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  title?: string;
  action?: React.ReactNode;
  onClick?: () => void;
}

export default function Card({ children, className = '', title, action, onClick }: CardProps) {
  return (
    <div className={`glass-panel p-6 ${className}`} onClick={onClick}>
      {(title || action) && (
        <div className="flex justify-between items-center mb-4">
          {title && <h3 className="m-0 text-lg font-semibold">{title}</h3>}
          {action && <div>{action}</div>}
        </div>
      )}
      <div>{children}</div>
    </div>
  );
}
