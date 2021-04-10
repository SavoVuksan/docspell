module Messages.Comp.UserForm exposing (..)


type alias Texts =
    { login : String
    , state : String
    , email : String
    , password : String
    }


gb : Texts
gb =
    { login = "Login"
    , state = "State"
    , email = "E-Mail"
    , password = "Password"
    }
