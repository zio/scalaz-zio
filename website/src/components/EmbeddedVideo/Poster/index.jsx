import React from 'react';
import clsx from 'clsx';
import { FaYoutube } from 'react-icons/fa';

import styles from './styles.module.css';

export default function Poster({
  className,
  posterUrl,
  onPointerOver,
  onClick,
}) {
  return (
    <div
      className={clsx('card aspect-video', styles.poster, className)}
      style={{ backgroundImage: `url(${posterUrl})` }}
      onPointerOver={onPointerOver}
      onClick={onClick}
    >
      <FaYoutube className={styles.posterYTButton} />
    </div>
  );
}
