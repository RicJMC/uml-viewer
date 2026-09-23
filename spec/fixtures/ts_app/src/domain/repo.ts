import type { Book } from "./book";

export interface Repo {
  get(isbn: string): Book | undefined;
}
