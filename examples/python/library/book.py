"""A book and its availability for borrowing."""


class Book:
    def __init__(self, title):
        self.title = title
        self.available = True

    def borrow(self):
        if not self.available:
            return False
        self.available = False
        return True

    def return_book(self):
        self.available = True
