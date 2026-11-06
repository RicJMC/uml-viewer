#pragma once

namespace store {
class Node {
public:
  explicit Node(int value);
  int value() const;

private:
  int value_;
};
}
