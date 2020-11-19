# this is a supporting file for the oceanix command

# it's a function which builds a single json file, which tells oceanix
# what to provision & deploy.

{
  network-file,
  create-image ? null,
  pkgs ? (import <nixpkgs> {})
}:
let
  selectAttrs = set : list : # selectAttrs {x = 1; y = 2;} ["x"] => {x = 1;}
  builtins.intersectAttrs
  (builtins.listToAttrs (map (x:{name=x; value=true;}) list))
  set;
  
  network = import network-file;
  defaults = network.network.defaults or {"region" = "lon1";};
  machines = builtins.removeAttrs network ["network"];
  evalConfig = import "${pkgs.path}/nixos/lib/eval-config.nix";

  machineConfig = machine-name : # a function which evals a machine's
                                 # config to a final form
  (evalConfig {
    modules =
      let
        machine = machines."${machine-name}";
        ssh-key = machine.ssh-key or defaults.ssh-key or "";
        ssh-module = {...}: if ssh-key == "" then {} else {
          users.users.root.openssh.authorizedKeys.keys = [ssh-key];
        };
      in
      [
        ./digitalocean.nix # required config
        machine.module # user config
        ssh-module # maybe root key
      ];
  }).config;

  # system-root is the derivation for the profile - this is what a
  # machine config ultimately produces. Amongst other things it
  # includes a script ./activate which makes the configuration take
  # effect (starts services, creates users, etc etc)
  system-root = machine : (machineConfig machine).system.build.toplevel;

  # this uses machineConfig to build a disk image for a machine.
  buildImage = machine-name :
  (import "${pkgs.path}/nixos/lib/make-disk-image.nix")  {
    pkgs = pkgs;
    lib = pkgs.lib;
    config = machineConfig machine-name;
    format = "qcow2-compressed";
    name = machine-name;
  };
  
  buildMachine = machine-name : # this outputs some stuff to go in a
                                # json file
  let machine = machines."${machine-name}"; in
  {
    name = machine-name;
    value =
      (selectAttrs (defaults // machine) # the deployment info
         ["size" "region" "ssh-key" "image"]) //
      { system = system-root machine-name; }; # and the system profile to deploy
  };
  
  result = builtins.listToAttrs (
    map buildMachine (builtins.attrNames machines)
  );
in
if create-image != null then buildImage create-image
else
pkgs.writeTextFile
{
  name = "${network.network.name}.json";
  text = builtins.toJSON result;
}
