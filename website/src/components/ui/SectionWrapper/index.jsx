import React from 'react';

export default function SectionWrapper({ title, children }) {
  return (
    <section className="py-20">
      {title ? (
        <div className="container mb-10">
          <h2 className="text-center text-4xl font-bold">{title}</h2>
        </div>
      ) : null}

      {children}
    </section>
  );
}
