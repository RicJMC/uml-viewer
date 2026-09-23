import type { Book } from "@domain/book";
import type { Repo } from "../domain/repo";
import { Service } from "../domain/service";

export class LoanRepo extends Service implements Repo {
  run(): void {}

  get(isbn: string): Book | undefined {
    if (isbn) {
      return { title: isbn };
    }
    return undefined;
  }
}

export function issue(isbn: string): string {
  if (!isbn) {
    return "";
  }
  return isbn;
}

function _audit(msg: string): string {
  return msg;
}
