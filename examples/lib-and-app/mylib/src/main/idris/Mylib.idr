module Mylib

export
greeting : String -> String
greeting name = "Hello, " ++ name ++ ", from mylib!"
