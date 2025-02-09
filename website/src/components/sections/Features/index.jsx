import React from 'react';
import { FaCheck } from 'react-icons/fa6';

import SectionWrapper from '@site/src/components/ui/SectionWrapper';

import { features } from './data';

export default function Features() {
  return (
    <SectionWrapper title="Features">
      <div className="container grid list-none grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
        {features.map((item, idx) => (
          <div key={`features-${idx}`} className="card">
            <div className="card__header flex flex-col items-start gap-5">
              <div className="bg-accent/10 flex h-10 w-10 items-center justify-center rounded-full p-2">
                <FaCheck className="text-accent" />
              </div>

              <h2 className="text-xl font-bold">{item.title}</h2>
            </div>
            <div className="card__body text-zinc-900 dark:text-zinc-400">
              <p>{item.content}</p>
            </div>
          </div>
        ))}
      </div>
    </SectionWrapper>
  );
}
