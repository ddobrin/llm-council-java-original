import { createBrowserRouter, RouterProvider } from 'react-router';
import MainLayout from './views/MainLayout';
import CouncilView from './views/CouncilView';
import TracingView from './views/TracingView';

const router = createBrowserRouter([
  {
    element: <MainLayout />,
    children: [
      {
        path: '/',
        element: <CouncilView />,
      },
      {
        path: '/traces',
        element: <TracingView />,
      },
    ],
  },
]);

export default function App() {
  return <RouterProvider router={router} />;
}
