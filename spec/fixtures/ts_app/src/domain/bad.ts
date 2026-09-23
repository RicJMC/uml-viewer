import { issue } from "../app/loan";

export function leak(): string {
  return issue("x");
}
