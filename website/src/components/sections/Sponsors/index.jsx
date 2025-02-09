import React from 'react';
import Link from '@docusaurus/Link';

import SectionWrapper from '@site/src/components/ui/SectionWrapper';

import { sponsors } from './data';

export default function Sponsors() {
  return (
    <SectionWrapper title="Our Sponsors">
      <div className="container">
        <div className="mx-auto flex w-full max-w-7xl flex-wrap items-center justify-around gap-12">
          {sponsors.map((sponsor, idx) => (
            <div
              key={`sponsor-${idx}`}
              className="flex flex-col items-center gap-2"
            >
              <Link
                className="rounded-xl p-2.5 grayscale invert-[50%] hover:bg-white hover:filter-none"
                href={sponsor.imageLink}
              >
                <img
                  src={`${sponsor.image}`}
                  alt={`${sponsor.imageAlt}`}
                  height={40}
                />
              </Link>

              <span className="font-semibold">{sponsor.content}</span>
            </div>
          ))}
        </div>
      </div>
    </SectionWrapper>
  );
}
