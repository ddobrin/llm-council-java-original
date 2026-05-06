import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './themes/llm-council/styles.css';

const root = createRoot(document.getElementById('outlet')!);
root.render(createElement(App));
