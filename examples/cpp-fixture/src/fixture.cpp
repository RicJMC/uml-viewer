#include "app/controller.hpp"

int model::Node::id() const { return 1; }

store::Node::Node(int value) : value_{value} {}

int store::Node::value() const { return value_; }

int app::Controller::run(int value) const { return value; }

int app::Controller::run(double value) const { return static_cast<int>(value); }

store::Node app::Controller::resolve(const store::Node& value) const {
  return value;
}
